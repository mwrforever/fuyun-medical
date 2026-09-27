package com.fuyun.iotsimulator.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 设备状态帧上报器单测（P2 PR-2 Task 14 Step 2）：剧本档位经 Monitor 同服务 status 属性帧
 * 上行——组帧结构与既有遥测帧同 topic 同服务（IoTDA 无设备侧状态上报主题，设备状态由连接
 * 派生，业务档位只能以属性承载，实测结论见类注记）。
 */
@ExtendWith(MockitoExtension.class)
class DeviceStatusReporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private IotdaMqttClient mqttClient;

    @Captor
    private ArgumentCaptor<String> payloadCaptor;

    @Test
    @DisplayName("档位上报：Monitor 同服务 status 属性帧，复用物模型上行通道发布")
    void reportsPhaseAsMonitorStatusPropertyFrame() throws Exception {
        DeviceStatusReporter reporter = new DeviceStatusReporter(mqttClient);
        reporter.reportPhase("LOW");

        verify(mqttClient).publish(payloadCaptor.capture());
        JsonNode frame = MAPPER.readTree(payloadCaptor.getValue());
        JsonNode service = frame.path("services").get(0);
        assertThat(service.path("service_id").asText()).as("与遥测帧同服务 Monitor").isEqualTo("Monitor");
        assertThat(service.path("properties").path("status").asText())
                .as("档位码原值承载（剧本档位词表）")
                .isEqualTo("LOW");
    }
}
