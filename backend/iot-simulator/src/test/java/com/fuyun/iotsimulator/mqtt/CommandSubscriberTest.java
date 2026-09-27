package com.fuyun.iotsimulator.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 命令下行订阅器单测（P2 PR-2 Task 14 Step 3，先写后落码）：订阅过滤器与 qos、命令回调挂接、
 * 应答回执主题与 result_code 语义（0=成功/1=失败——官方口径，平台据其归 SUCCESS/FAILED 终态，
 * 供 Task 8 结果回推链路真栈演示）。命令处理经单线程执行器异步执行（Paho 同步客户端禁止在
 * messageArrived 回调线程内阻塞发布——等待完成令牌与回执确认同线程，死锁防线），断言以
 * Mockito timeout 轮询替代时序假设。
 */
@ExtendWith(MockitoExtension.class)
class CommandSubscriberTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 命令下行主题样例（平台下发，request_id 由平台生成） */
    private static final String COMMAND_TOPIC = "$oc/devices/dev-001/sys/commands/request_id=5f4d-9a2c-11ef";

    /** 应答回执主题（与命令帧 request_id 一致——官方要求响应回执携带同值） */
    private static final String RESPONSE_TOPIC = "$oc/devices/dev-001/sys/commands/response/request_id=5f4d-9a2c-11ef";

    @Mock
    private IotdaMqttClient mqttClient;

    @Captor
    private ArgumentCaptor<IMqttMessageListener> listenerCaptor;

    @Captor
    private ArgumentCaptor<String> payloadCaptor;

    /** 记录型剧本回调：返回值由用例预设（true=执行成功） */
    private final java.util.List<String> receivedCommands = new java.util.ArrayList<>();

    private boolean handlerResult = true;

    private CommandSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new CommandSubscriber(mqttClient, "dev-001", (commandName, parasJson) -> {
            receivedCommands.add(commandName);
            return handlerResult;
        });
    }

    @Test
    @DisplayName("订阅形态：过滤器 $oc/devices/{id}/sys/commands/#、qos=1（官方允许 # 通配覆盖任意 request_id）")
    void subscribesCommandWildcardFilterAtQosOne() throws Exception {
        subscriber.subscribe();

        verify(mqttClient).subscribe(eq("$oc/devices/dev-001/sys/commands/#"), eq(1), listenerCaptor.capture());
        assertThat(listenerCaptor.getValue()).as("下行监听挂接到客户端").isNotNull();
    }

    @Test
    @DisplayName("支持命令回执成功：剧本回调收到命令名，回执 result_code=0 于 request_id 对应主题")
    void respondsSuccessReceiptForSupportedCommand() throws Exception {
        subscriber.subscribe();
        verify(mqttClient).subscribe(anyString(), anyInt(), listenerCaptor.capture());

        listenerCaptor
                .getValue()
                .messageArrived(
                        COMMAND_TOPIC,
                        new MqttMessage("{\"command_name\":\"PAUSE_INFUSION\",\"service_id\":\"Monitor\",\"paras\":{}}"
                                .getBytes(StandardCharsets.UTF_8)));

        assertThat(receivedCommands).as("剧本回调挂接：命令名原值透传").contains("PAUSE_INFUSION");
        verify(mqttClient, timeout(2000)).publishTo(eq(RESPONSE_TOPIC), payloadCaptor.capture());
        JsonNode receipt = MAPPER.readTree(payloadCaptor.getValue());
        assertThat(receipt.path("result_code").asInt())
                .as("执行成功回执 result_code=0（平台归 SUCCESS 终态）")
                .isZero();
    }

    @Test
    @DisplayName("不支持命令回执失败：剧本回调拒绝（false）时回执 result_code=1")
    void respondsFailureReceiptWhenHandlerRejectsCommand() throws Exception {
        handlerResult = false;
        subscriber.subscribe();
        verify(mqttClient).subscribe(anyString(), anyInt(), listenerCaptor.capture());

        listenerCaptor
                .getValue()
                .messageArrived(
                        COMMAND_TOPIC,
                        new MqttMessage("{\"command_name\":\"SHUTDOWN_DEVICE\",\"paras\":{}}"
                                .getBytes(StandardCharsets.UTF_8)));

        verify(mqttClient, timeout(2000)).publishTo(eq(RESPONSE_TOPIC), payloadCaptor.capture());
        assertThat(MAPPER.readTree(payloadCaptor.getValue()).path("result_code").asInt())
                .as("执行失败回执 result_code=1（非 0 即失败，平台归 FAILED 终态）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("缺 command_name 视作不支持：不挂剧本回调，回执失败口径")
    void respondsFailureWhenCommandNameMissing() throws Exception {
        subscriber.subscribe();
        verify(mqttClient).subscribe(anyString(), anyInt(), listenerCaptor.capture());

        listenerCaptor
                .getValue()
                .messageArrived(COMMAND_TOPIC, new MqttMessage("{\"paras\":{}}".getBytes(StandardCharsets.UTF_8)));

        assertThat(receivedCommands).as("无命令名不挂剧本回调").isEmpty();
        verify(mqttClient, timeout(2000)).publishTo(eq(RESPONSE_TOPIC), payloadCaptor.capture());
        assertThat(MAPPER.readTree(payloadCaptor.getValue()).path("result_code").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("非 JSON 命令帧：不挂剧本回调、可回执时走失败口径、回调线程零外抛")
    void respondsFailureAndSwallowsForMalformedFrame() throws Exception {
        subscriber.subscribe();
        verify(mqttClient).subscribe(anyString(), anyInt(), listenerCaptor.capture());

        assertThatCode(() -> listenerCaptor
                        .getValue()
                        .messageArrived(COMMAND_TOPIC, new MqttMessage("not-json".getBytes(StandardCharsets.UTF_8))))
                .as("畸形帧不得向 Paho 回调线程外抛（外抛即断开连接）")
                .doesNotThrowAnyException();

        assertThat(receivedCommands).as("畸形帧不挂剧本回调").isEmpty();
        verify(mqttClient, timeout(2000)).publishTo(eq(RESPONSE_TOPIC), payloadCaptor.capture());
        assertThat(MAPPER.readTree(payloadCaptor.getValue()).path("result_code").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("request_id 缺失（主题非命令形态）：无法定位回执主题，仅记日志零发布")
    void skipsReceiptWhenRequestIdMissing() throws Exception {
        subscriber.subscribe();
        verify(mqttClient).subscribe(anyString(), anyInt(), listenerCaptor.capture());

        assertThatCode(() -> listenerCaptor
                        .getValue()
                        .messageArrived(
                                "$oc/devices/dev-001/sys/commands/unexpected",
                                new MqttMessage(
                                        "{\"command_name\":\"PAUSE_INFUSION\"}".getBytes(StandardCharsets.UTF_8))))
                .doesNotThrowAnyException();

        verify(mqttClient, timeout(2000).times(0)).publishTo(anyString(), anyString());
        assertThat(receivedCommands).as("无法归属请求的命令不挂剧本回调").isEmpty();
    }
}
