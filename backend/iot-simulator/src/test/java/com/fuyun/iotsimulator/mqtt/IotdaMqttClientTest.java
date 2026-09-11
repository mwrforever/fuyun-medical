package com.fuyun.iotsimulator.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.iotsimulator.telemetry.DeviceCredentialEncoder;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
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
 * IoTDA MQTT 客户端封装单测（BRIEF-PR4-01 §5）：连接选项（automaticReconnect/cleanSession/ssl 判别）、
 * 上行 topic 与 qos、断连回调日志路径、优雅断开幂等——MqttClient 以 Mockito 桩替，零真实网络。
 */
@ExtendWith(MockitoExtension.class)
class IotdaMqttClientTest {

    /** 一机一密三元组桩（值本身无业务含义，仅为格式合法性） */
    private static final DeviceCredentialEncoder.MqttCredential CREDENTIAL =
            new DeviceCredentialEncoder.MqttCredential("dev-001_0_0_2025041401", "dev-001", "aa".repeat(32));

    @Mock
    private MqttClient mqttClient;

    @Captor
    private ArgumentCaptor<MqttConnectOptions> optionsCaptor;

    @Captor
    private ArgumentCaptor<MqttMessage> messageCaptor;

    private IotdaMqttClient client;

    @BeforeEach
    void setUp() throws MqttException {
        client = new IotdaMqttClient(mqttClient, CREDENTIAL, "ssl://127.0.0.1:1883");
    }

    @Test
    @DisplayName("连接选项：automaticReconnect=true、cleanSession=false、一机一密 username/password，ssl 主机设 TLS 工厂")
    void connectsWithReconnectPersistentSessionAndTlsForSslHost() throws MqttException {
        client.connect();

        verify(mqttClient).connect(optionsCaptor.capture());
        MqttConnectOptions options = optionsCaptor.getValue();
        assertThat(options.isAutomaticReconnect()).as("断链自动重连（演示链路韧性）").isTrue();
        assertThat(options.isCleanSession()).as("持久会话（断链期间服务端保留订阅态）").isFalse();
        assertThat(options.getSocketFactory()).as("ssl:// 前缀主机启用 TLS").isNotNull();
        // 华为云 MQTT(S) 一机一密鉴权：CONNECT 报文 username=deviceId、password=HMAC 摘要（缺省即拒绝）
        assertThat(options.getUserName()).as("username = deviceId（一机一密三元组）").isEqualTo("dev-001");
        assertThat(new String(options.getPassword()))
                .as("password = 一机一密 HMAC 摘要（DeviceCredentialEncoder 产出）")
                .isEqualTo("aa".repeat(32));
    }

    @Test
    @DisplayName("非 ssl 主机不设置套接字工厂（本地联调明文 1883 场景）")
    void skipsSocketFactoryForPlainHost() throws MqttException {
        IotdaMqttClient plainClient = new IotdaMqttClient(mqttClient, CREDENTIAL, "tcp://127.0.0.1:1883");
        plainClient.connect();

        verify(mqttClient).connect(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getSocketFactory())
                .as("明文主机零 TLS 配置")
                .isNull();
    }

    @Test
    @DisplayName("上行发布：topic 为 $oc/devices/{deviceId}/sys/properties/report 且 qos=1、UTF-8 载荷")
    void publishesPropertiesReportAtQosOne() throws MqttException {
        client.publish("{\"services\":[]}");

        verify(mqttClient).publish(eq("$oc/devices/dev-001/sys/properties/report"), messageCaptor.capture());
        MqttMessage published = messageCaptor.getValue();
        assertThat(published.getQos()).as("上行 qos=1（至少一次，IoTDA 物模型上行口径）").isEqualTo(1);
        assertThat(new String(published.getPayload(), StandardCharsets.UTF_8)).isEqualTo("{\"services\":[]}");
    }

    @Test
    @DisplayName("断连回调：connectionLost 仅记录 error 日志不向外抛（Paho 自动重连兜底）")
    void logsConnectionLostWithoutRethrowing() throws MqttException {
        ArgumentCaptor<MqttCallback> callbackCaptor = ArgumentCaptor.forClass(MqttCallback.class);
        client.connect();
        verify(mqttClient).setCallback(callbackCaptor.capture());

        assertThatCode(() -> callbackCaptor
                        .getValue()
                        .connectionLost(new MqttException(new RuntimeException("broken pipe"))))
                .as("断连回调不得向外抛异常（重连交由 Paho automaticReconnect）")
                .doesNotThrowAnyException();
        assertThatCode(() -> callbackCaptor.getValue().messageArrived("t", new MqttMessage()))
                .as("上行-only 客户端不消费下行帧（回调空实现不得抛错）")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("优雅断开：已连接时先 disconnect 后 close，disconnect 抛异常仍继续 close（停机零传播）")
    void closesQuietlyEvenWhenDisconnectFails() throws MqttException {
        when(mqttClient.isConnected()).thenReturn(true);
        doThrow(new MqttException(new RuntimeException("already disconnected")))
                .when(mqttClient)
                .disconnect();

        assertThatCode(client::close).as("停机路径零传播").doesNotThrowAnyException();
        verify(mqttClient).disconnect();
        verify(mqttClient).close();
    }

    @Test
    @DisplayName("优雅断开：未连接时跳过 disconnect 仅 close（connect 失败后的清理路径）")
    void closesWithoutDisconnectWhenNeverConnected() throws MqttException {
        when(mqttClient.isConnected()).thenReturn(false);

        assertThatCode(client::close).doesNotThrowAnyException();
        verify(mqttClient, never()).disconnect();
        verify(mqttClient).close();
    }

    @Test
    @DisplayName("优雅断开：disconnect 与 close 双双失败仍零传播（进程退出不受残留连接影响）")
    void swallowsBothDisconnectAndCloseFailures() throws MqttException {
        when(mqttClient.isConnected()).thenReturn(true);
        doThrow(new MqttException(new RuntimeException("disconnect failed")))
                .when(mqttClient)
                .disconnect();
        doThrow(new MqttException(new RuntimeException("close failed")))
                .when(mqttClient)
                .close();

        assertThatCode(client::close).as("双步失败仍不得向外传播停机异常").doesNotThrowAnyException();
        verify(mqttClient).close();
    }
}
