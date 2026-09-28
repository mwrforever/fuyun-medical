package com.fuyun.iotsimulator.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.iotsimulator.telemetry.TelemetryPayloadBuilder;
import org.eclipse.paho.client.mqttv3.MqttException;

/**
 * 设备状态帧上报器（P2 PR-2 Task 14）：剧本档位变化时上行 status 属性帧——剧本状态机经
 * {@code drainPhaseTransitions} 产出的档位码在此转为物模型属性帧发布，供 Task 7 透传规则
 * 与设备状态链演示（IoTDA 推送形态 resource=device.property 按 L-3 冻结映射展开为
 * metricCode=status 的标准遥测消息，后端告警规则/输液看板可按其配置消费）。
 *
 * <p><b>官方形态实测结论（2026-09-27，华为云 IoTDA 文档复核）</b>：设备侧<b>无独立的状态
 * 上报主题</b>——在线/离线/异常由平台按 MQTT 连接状态判别并经规则引擎「设备状态变更通知」
 * 数据源转发（设备侧不参与上报）；设备侧业务状态承载仅有物模型属性上报通道。故本实现取
 * 简报两形态中的「properties/report 携 status 属性」形态：复用
 * {@code $oc/devices/{id}/sys/properties/report} 主题与 Monitor 同服务（物模型同服务展开
 * 属性口径），status 档位帧与遥测帧同通道，后端解析链零新增形态。
 */
public class DeviceStatusReporter {

    /** 状态属性键（Monitor 同服务展开；剧本档位码原值承载，原生属性名直通契约） */
    public static final String PROP_STATUS = "status";

    /** 物模型上行客户端（复用遥测帧同一连接与 properties/report 主题） */
    private final IotdaMqttClient client;

    /** JSON 组装器：ObjectNode 有序构造（与 TelemetryPayloadBuilder 同构键序） */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 构造状态帧上报器。
     *
     * @param client 物模型上行 MQTT 客户端，非空；生产为真实实例，单测为 Mockito 桩
     */
    public DeviceStatusReporter(IotdaMqttClient client) {
        this.client = client;
    }

    /**
     * 上行一帧 status 属性帧（Monitor 同服务）：
     * {@code {"services":[{"service_id":"Monitor","properties":{"status":"LOW"}}]}}。
     *
     * @param phaseCode 剧本档位码，非空；来源：Scenario.drainPhaseTransitions（NORMAL/DECAYING/
     *                  LOW/CRITICAL/STARVED/PAUSED 词表）
     * @throws MqttException 发布失败（断链中等）；单帧失败由上行周期体统一吞并（不中断循环）
     */
    public void reportPhase(String phaseCode) throws MqttException {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode service = root.putArray("services").addObject();
        service.put("service_id", TelemetryPayloadBuilder.SERVICE_ID);
        service.putObject("properties").put(PROP_STATUS, phaseCode);
        client.publish(root.toString());
    }
}
