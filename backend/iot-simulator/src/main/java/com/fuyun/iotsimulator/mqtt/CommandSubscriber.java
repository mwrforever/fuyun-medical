package com.fuyun.iotsimulator.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 命令下行订阅器（P2 PR-2 Task 14）：订阅 IoTDA 命令下发主题、解析命令帧、挂接剧本回调并
 * 回执执行结果——命令下行真栈链路的设备侧末端（平台下发命令 → 设备执行 → 回执 → 平台归终态
 * → 规则引擎「异步命令状态」转发 → 后端 Task 8 消费端回推终态）。
 *
 * <p><b>主题与回执契约（官方口径，2026-09-27 实测核对）</b>：订阅过滤器
 * {@code $oc/devices/{id}/sys/commands/#}（官方允许 {request_id} 段以 # 通配），qos=1；
 * 命令帧经 {@code .../sys/commands/request_id={request_id}} 下达，应答回执发布至
 * {@code .../sys/commands/response/request_id={request_id}}（request_id 必须与命令帧一致），
 * 载荷 {@code {"result_code":0,"result_msg":...}}——<b>result_code 0=成功、非 0=失败</b>；
 * 平台据回执归命令终态（0→SUCCESS，非 0→FAILED），即 Task 8 消费端第五形态
 * （resource=device.command.status，body.status ∈ DELIVERED/SUCCESS/FAILED/TIMEOUT/EXPIRED/
 * REMOVED 词表）可解析为 SUCCESS/FAILED 终态的回执来源。
 *
 * <p><b>线程纪律</b>：Paho 同步客户端禁止在 messageArrived 回调线程内阻塞发布（等待完成令牌
 * 与回执确认同线程，存在死锁风险），命令处理移交单线程命名执行器（daemon——优雅停机由
 * IotdaMqttClient.close 断链收口，daemon 线程不阻塞 JVM 退出）；单线程保证命令按到达序执行，
 * 暂停/恢复等状态变更与上报周期线程的并发由剧本侧 synchronized 兜底。
 *
 * <p><b>异常口径</b>：命令帧畸形/缺 command_name/剧本拒绝 → 回执 result_code=1（可定位回执
 * 主题时尽力闭合命令，平台归 FAILED 而非无限等待）；request_id 缺失（主题非命令形态）→ 无法
 * 定位回执主题，仅记录日志（平台侧按超时归 TIMEOUT/EXPIRED 终态）；回调线程零外抛
 * （外抛即 Paho 断开连接）。
 */
public class CommandSubscriber {

    /** 命令下行订阅过滤器模板：%s = deviceId（# 通配覆盖任意 request_id，官方允许） */
    public static final String TOPIC_COMMAND_FILTER_TEMPLATE = "$oc/devices/%s/sys/commands/#";

    /** 命令应答回执主题模板：%1$s = deviceId、%2$s = request_id（与命令帧同值） */
    public static final String TOPIC_COMMAND_RESPONSE_TEMPLATE =
            "$oc/devices/%1$s/sys/commands/response/request_id=%2$s";

    /** 命令帧主题内请求标识前缀（request_id={id} 段提取锚） */
    private static final String REQUEST_ID_PREFIX = "request_id=";

    /** 命令帧 JSON 键：命令名（产品物模型命令定义名） */
    private static final String KEY_COMMAND_NAME = "command_name";

    /** 命令帧 JSON 键：命令参数集 */
    private static final String KEY_PARAS = "paras";

    /** 回执 JSON 键：执行结果码（0=成功、非 0=失败，官方词表） */
    private static final String KEY_RESULT_CODE = "result_code";

    /** 回执 JSON 键：结果描述（官方可选字段，留痕承载） */
    private static final String KEY_RESULT_MSG = "result_msg";

    /** 执行成功结果码（平台归 SUCCESS 终态） */
    private static final int RESULT_CODE_SUCCESS = 0;

    /** 执行失败结果码（平台归 FAILED 终态） */
    private static final int RESULT_CODE_FAILURE = 1;

    /** 下行命令处理线程名（单线程命名执行器，宪法 B.3-4 线程纪律） */
    private static final String COMMAND_THREAD_NAME = "iot-simulator-command";

    private static final Logger log = LoggerFactory.getLogger(CommandSubscriber.class);

    /** 物模型 MQTT 客户端（订阅与回执发布共用同一连接） */
    private final IotdaMqttClient client;

    /** 设备标识（订阅过滤器与回执主题展开） */
    private final String deviceId;

    /** 剧本命令回调（命令执行判定与剧本状态变更挂接点） */
    private final ScenarioCommandHandler handler;

    /** 命令帧解析器（ObjectMapper 线程安全，单实例复用） */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 单线程 daemon 执行器：命令处理与回执发布脱离 Paho 回调线程（死锁防线 + 到达序保序） */
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, COMMAND_THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 剧本命令回调函数接口：命令执行判定（回执 result_code 依据）。
     */
    @FunctionalInterface
    public interface ScenarioCommandHandler {

        /**
         * 执行一条平台命令。
         *
         * @param commandName 命令名（命令帧 command_name 原值），非空
         * @param parasJson   命令参数 JSON 文本（命令帧 paras 原文），可空；无参命令为空串
         * @return true=执行成功（回执 result_code=0）；false=不支持或执行失败（回执 result_code=1）
         */
        boolean onCommand(String commandName, String parasJson);
    }

    /**
     * 构造命令下行订阅器。
     *
     * @param client    物模型 MQTT 客户端，非空；生产为真实实例，单测为 Mockito 桩
     * @param deviceId  设备标识，非空；订阅过滤器与回执主题展开依据
     * @param handler   剧本命令回调，非空；生产为 Scenario::handleCommand 方法引用
     */
    public CommandSubscriber(IotdaMqttClient client, String deviceId, ScenarioCommandHandler handler) {
        this.client = client;
        this.deviceId = deviceId;
        this.handler = handler;
    }

    /**
     * 注册命令下行订阅（qos=1，启动期调用一次）：订阅失败即受检上抛交启动方 fail-fast
     * （命令下行是演示链路组成，静默缺失只会把故障后移到平台命令超时）。
     *
     * @throws MqttException 订阅失败（断链中等）；由调用方决定终止启动或重试
     */
    public void subscribe() throws MqttException {
        String filter = String.format(TOPIC_COMMAND_FILTER_TEMPLATE, deviceId);
        client.subscribe(filter, 1, (topic, message) -> commandExecutor.submit(() -> handleCommand(topic, message)));
        log.info("命令下行订阅已注册：deviceId={}，filter={}，qos=1", deviceId, filter);
    }

    /**
     * 处理一帧命令（执行器线程内）：提取 request_id → 解析命令名/参数 → 挂剧本回调 → 回执。
     * 全程捕获异常（回调线程零外抛——外抛即 Paho 断开连接）。
     *
     * @param topic   命令帧主题（request_id 提取源），非空
     * @param message 命令帧报文，非空；UTF-8 载荷
     */
    private void handleCommand(String topic, MqttMessage message) {
        String requestId = extractRequestId(topic);
        if (requestId == null || requestId.isBlank()) {
            // 主题无 request_id 段：无法定位回执主题，仅留痕（平台按超时归 TIMEOUT/EXPIRED 终态）
            log.error("命令帧主题缺 request_id 段，无法回执（平台将按超时归终态）：deviceId={}，topic={}", deviceId, topic);
            return;
        }
        String payloadText = new String(message.getPayload(), StandardCharsets.UTF_8);
        boolean success;
        String commandName = null;
        try {
            JsonNode root = mapper.readTree(payloadText);
            commandName = root.path(KEY_COMMAND_NAME).asText(null);
            // 缺 command_name 的帧无法归因到命令定义：视作不支持（不挂剧本回调，回执失败口径）
            success = commandName != null && handler.onCommand(commandName, parasAsText(root));
        } catch (Exception e) {
            // 畸形帧：尽力回执失败（可定位回执主题即闭合命令），原因不含帧原文
            log.error("命令帧解析失败，回执失败口径：deviceId={}，requestId={}，原因={}", deviceId, requestId, e.getMessage());
            respond(requestId, RESULT_CODE_FAILURE, "命令帧解析失败");
            return;
        }
        if (success) {
            log.info("命令执行成功并回执：deviceId={}，command={}，requestId={}", deviceId, commandName, requestId);
            respond(requestId, RESULT_CODE_SUCCESS, "success");
        } else {
            log.warn("命令执行失败并回执：deviceId={}，command={}，requestId={}", deviceId, commandName, requestId);
            respond(requestId, RESULT_CODE_FAILURE, "剧本未支持或执行失败：" + commandName);
        }
    }

    /**
     * 发布执行回执（qos=1，经客户端任意主题通道）。
     *
     * @param requestId  请求标识（与命令帧一致），非空
     * @param resultCode 结果码（0=成功、1=失败）
     * @param resultMsg  结果描述（留痕承载）
     */
    private void respond(String requestId, int resultCode, String resultMsg) {
        String receipt = mapper.createObjectNode()
                .put(KEY_RESULT_CODE, resultCode)
                .put(KEY_RESULT_MSG, resultMsg)
                .toString();
        try {
            client.publishTo(String.format(TOPIC_COMMAND_RESPONSE_TEMPLATE, deviceId, requestId), receipt);
        } catch (MqttException e) {
            // 回执发布失败（断链等）：仅记录——平台侧按超时归终态，重连后新命令恢复链路
            log.error("命令回执发布失败：deviceId={}，requestId={}，原因={}", deviceId, requestId, e.getMessage());
        }
    }

    /**
     * 提取命令参数为 JSON 文本（挂接剧本回调的 paras 原文承载）：对象/数组以紧凑 JSON 文本，
     * 标量以文本，缺失为空串。
     *
     * @param root 命令帧根节点，非空
     * @return paras JSON 文本（可空业务语义以空串表达），非空
     */
    private static String parasAsText(JsonNode root) {
        JsonNode paras = root.path(KEY_PARAS);
        return paras.isValueNode() ? paras.asText("") : paras.toString();
    }

    /**
     * 从命令帧主题提取 request_id（{@code request_id={id}} 段，取段内无 "/" 前缀约定——
     * IoTDA request_id 为平台生成的 UUID/标识，不含路径分隔符）。
     *
     * @param topic 命令帧主题，非空
     * @return request_id 文本；段不存在返回 null
     */
    private static String extractRequestId(String topic) {
        int prefixIndex = topic.indexOf(REQUEST_ID_PREFIX);
        if (prefixIndex < 0) {
            return null;
        }
        String value = topic.substring(prefixIndex + REQUEST_ID_PREFIX.length());
        int slashIndex = value.indexOf('/');
        return slashIndex >= 0 ? value.substring(0, slashIndex) : value;
    }
}
