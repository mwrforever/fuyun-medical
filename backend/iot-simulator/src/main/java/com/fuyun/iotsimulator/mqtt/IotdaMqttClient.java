package com.fuyun.iotsimulator.mqtt;

import com.fuyun.iotsimulator.telemetry.DeviceCredentialEncoder.MqttCredential;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocketFactory;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IoTDA MQTT 客户端封装（BRIEF-PR4-01 §5，Paho mqttv3 同步客户端薄封装）。
 *
 * <p><b>连接语义</b>：{@code automaticReconnect=true}（断链由 Paho 自动重连兜底）+
 * {@code cleanSession=false}（持久会话，断链期间服务端保留会话态与订阅上下文——MQTT 3.1.1
 * 持久会话订阅由 broker 侧保留，重连无需重订阅）；mqttHost 为 {@code ssl://} 前缀时启用 TLS
 * （生产 IoTDA 8883 口，JVM 默认信任库承载公有 CA）；明文 tcp:// 仅限本地联调 broker。
 * topic 常量 = {@code $oc/devices/{deviceId}/sys/properties/report}（物模型属性上行标准主题），
 * 上行 qos=1（至少一次，IoTDA 物模型上行口径）。
 *
 * <p><b>通道面</b>（P2 PR-2 Task 14 扩展）：{@link #publish}（属性上行）、
 * {@link #publishTo}（任意主题发布——命令应答回执走此通道）、
 * {@link #subscribe}（下行订阅透传——命令下行由 CommandSubscriber 挂接监听）。
 *
 * <p><b>凭证边界</b>：一机一密三元组仅经构造参数传入（DeviceCredentialEncoder 产出），连接时写入
 * CONNECT 报文 username/password（华为云 MQTT(S) 鉴权要求：username=deviceId、password=HMAC 摘要），
 * username/deviceId 可入日志，<b>password 禁入任何日志与异常消息</b>。
 *
 * <p>封装为薄委托：连接/发布/订阅动作直接透传 Paho，异常按 MqttException 受检上抛交调用方
 * （上行周期体吞并单帧失败，优雅停机吞并关闭失败）。
 */
public class IotdaMqttClient {

    /** 物模型属性上行主题模板：%s = deviceId（IoTDA 标准主题词表） */
    public static final String TOPIC_PROPERTIES_REPORT_TEMPLATE = "$oc/devices/%s/sys/properties/report";

    private static final Logger log = LoggerFactory.getLogger(IotdaMqttClient.class);

    /** TLS 加密地址前缀：命中即配置 TLS 套接字工厂 */
    private static final String SSL_URI_PREFIX = "ssl://";

    /** Paho 同步客户端（构造期注入，生产为真实实例，单测为 Mockito 桩） */
    private final MqttClient client;

    /** 上行目标主题（构造期按 deviceId 展开模板） */
    private final String topic;

    /** 连接地址（ssl 判别与连接日志用，不含敏感值） */
    private final String mqttHost;

    /** 设备标识（日志业务标识；取自凭证 username） */
    private final String deviceId;

    /**
     * 一机一密连接三元组（connect 时写入 CONNECT 报文 username/password；clientId 已在 Paho 客户端
     * 构造期使用，三元组其余两值仅本类持有，password 禁入日志）。
     */
    private final MqttCredential credential;

    /**
     * 生产构造：自建 Paho 客户端（内存持久化——持久会话态由 broker 侧承载，客户端侧无落盘诉求）。
     *
     * @param mqttHost MQTT 接入地址，非空；ssl:// 前缀启用 TLS
     * @param credential 一机一密连接三元组，非空；来源：DeviceCredentialEncoder.encode
     * @throws MqttException Paho 客户端创建失败（地址非法等）
     */
    public IotdaMqttClient(String mqttHost, MqttCredential credential) throws MqttException {
        this(new MqttClient(mqttHost, credential.clientId(), new MemoryPersistence()), credential, mqttHost);
    }

    /**
     * 注入构造（包内单测用）：外部提供 Paho 客户端桩。
     *
     * @param client Paho 客户端，非空
     * @param credential 一机一密连接三元组，非空
     * @param mqttHost 连接地址，非空
     */
    IotdaMqttClient(MqttClient client, MqttCredential credential, String mqttHost) throws MqttException {
        this.client = client;
        this.mqttHost = mqttHost;
        this.credential = credential;
        this.deviceId = credential.username();
        this.topic = String.format(TOPIC_PROPERTIES_REPORT_TEMPLATE, deviceId);
        // 断连回调仅记录中文 error（重连交由 Paho automaticReconnect）；上行-only 客户端不消费下行帧
        this.client.setCallback(new SimulatorMqttCallback());
    }

    /**
     * 建立 MQTT 连接：CONNECT 报文携带一机一密 username/password（华为云 MQTT(S) 鉴权口径——
     * username=deviceId、password=DeviceCredentialEncoder 产出的 HMAC 摘要，服务端按三元组复算
     * 比对，缺省即鉴权拒绝），口令由服务端按 clientId 时间戳段复算。
     *
     * @throws MqttException 建链失败（网络不可达/鉴权拒绝等）；由调用方决定重试或终止启动
     */
    public void connect() throws MqttException {
        MqttConnectOptions options = new MqttConnectOptions();
        // 一机一密鉴权：CONNECT 报文 username=deviceId、password=HMAC 摘要（真实 IoTDA 必填）
        options.setUserName(credential.username());
        options.setPassword(credential.password().toCharArray());
        // 断链自动重连：演示链路韧性兜底（Paho 内建指数退避）
        options.setAutomaticReconnect(true);
        // 持久会话：断链期间服务端保留会话态，重连后不丢订阅上下文
        options.setCleanSession(false);
        if (mqttHost.startsWith(SSL_URI_PREFIX)) {
            // 生产 IoTDA 8883 口 TLS：JVM 默认信任库承载公有 CA，不自带信任材料（镜像内零密钥）
            options.setSocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault());
        }
        client.connect(options);
        log.info("MQTT 已连接：deviceId={}，host={}", deviceId, mqttHost);
    }

    /**
     * 上行一帧物模型 JSON 至默认属性上行主题（qos=1）。
     *
     * @param payload 物模型 JSON 串，非空；来源：TelemetryPayloadBuilder.next / DeviceStatusReporter.reportPhase
     * @throws MqttException 发布失败（断链中等）；单帧失败由上行周期体吞并，下周期重试
     */
    public void publish(String payload) throws MqttException {
        publishTo(topic, payload);
    }

    /**
     * 发布一帧至任意主题（qos=1）：属性上行默认主题外的通道——命令应答回执
     * （$oc/devices/{id}/sys/commands/response/request_id={id}，P2 PR-2 Task 14）等。
     *
     * @param targetTopic 目标主题，非空；调用方按 IoTDA 标准主题词表展开
     * @param payload     JSON 串，非空；UTF-8 编码承载
     * @throws MqttException 发布失败（断链中等）；调用方决定吞并或上抛
     */
    public void publishTo(String targetTopic, String payload) throws MqttException {
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        // qos=1 至少一次：物模型上行与命令回执统一口径（重复帧由后端唯一约束幂等去重）
        message.setQos(1);
        client.publish(targetTopic, message);
    }

    /**
     * 注册下行订阅（P2 PR-2 Task 14 命令下行通道）：topicFilter/qos/监听器透传 Paho——
     * 监听器回调运行在 Paho 回调线程，<b>回调内禁止同步阻塞调用本客户端</b>（等待完成令牌
     * 与报文确认同线程，同步客户端存在死锁风险），订阅方须自行移交处理线程。
     *
     * @param topicFilter 订阅过滤器，非空；IoTDA 允许 {request_id} 段以 # 通配（官方口径）
     * @param qos         服务质量档（命令下行取 1 至少一次）
     * @param listener    下行帧监听器，非空；（topic, message）回调
     * @throws MqttException 订阅失败（断链中等）；由调用方决定重试或终止启动
     */
    public void subscribe(String topicFilter, int qos, IMqttMessageListener listener) throws MqttException {
        client.subscribe(topicFilter, qos, listener);
    }

    /**
     * 优雅断开（shutdownHook 调用）：先 disconnect（已连接时）再 close，两步异常全吞——
     * 停机路径零传播，进程退出不受残留连接影响。
     */
    public void close() {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException e) {
            log.warn("MQTT 断开异常（停机路径忽略）：deviceId={}，原因={}", deviceId, e.getMessage());
        }
        try {
            client.close();
        } catch (MqttException e) {
            log.warn("MQTT 客户端关闭异常（停机路径忽略）：deviceId={}，原因={}", deviceId, e.getMessage());
        }
    }

    /**
     * 模拟设备回调：仅承载断连 error 日志路径；下行消费由各订阅经
     * {@link #subscribe(String, int, IMqttMessageListener)} 挂接专属监听器（不经本回调），
     * 本回调对残留下行帧保持空实现。
     */
    private final class SimulatorMqttCallback implements MqttCallback {

        /**
         * 连接断开回调：仅记录 error（含原因摘要），重连由 Paho automaticReconnect 自动执行。
         *
         * @param cause 断开原因（可空）；仅记录消息摘要，不含凭证
         */
        @Override
        public void connectionLost(Throwable cause) {
            log.error(
                    "MQTT 连接断开（Paho automaticReconnect 将自动重连）：deviceId={}，原因={}",
                    deviceId,
                    cause == null ? "未知原因" : cause.getMessage());
        }

        /** 无专属监听器的下行帧到达（空实现，不消费） */
        @Override
        public void messageArrived(String topic, MqttMessage message) {
            // 下行消费由各订阅专属监听器承载，本回调不消费
        }

        /** qos=1 投递完成回调（上行无业务动作，空实现） */
        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // qos=1 完成回调无业务动作
        }
    }
}
