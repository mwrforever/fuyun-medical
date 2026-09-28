package com.fuyun.iotsimulator.scenario;

import com.fuyun.iotsimulator.telemetry.TelemetryPayloadBuilder;
import java.util.List;

/**
 * 体征剧本（P2 PR-2 Task 14，既有行为封装）：P0 的固定 seed 确定性体征序列原样封装为剧本
 * 引擎形态——帧形（heartRate/spo2 双指标）、值域、确定性序列与升级前逐字符一致，vitals 档
 * 默认走本剧本（未配置 IOTDA_SCENARIO 时的行为零变化锚点）。
 *
 * <p>无档位概念（零公告）、不支持命令（handleCommand 走接口默认拒绝口径）。
 */
public class VitalsScenario implements Scenario {

    /** 既有确定性体征载荷构造器（同设备 seed 派生，序列与 P0 一致） */
    private final TelemetryPayloadBuilder payloadBuilder;

    /**
     * 构造体征剧本。
     *
     * @param payloadBuilder 体征载荷构造器，非空；来源：ScenarioFactory 按 deviceId 派生 seed 装配
     */
    public VitalsScenario(TelemetryPayloadBuilder payloadBuilder) {
        this.payloadBuilder = payloadBuilder;
    }

    /** @return 剧本名 vitals（配置档词表） */
    @Override
    public String name() {
        return "vitals";
    }

    /**
     * 产出一帧既有体征帧（heartRate/spo2，确定性序列）。
     *
     * @return 物模型 JSON 串，非空；与 P0 TelemetryPayloadBuilder.next 产物逐字符一致
     */
    @Override
    public String nextFrame() {
        return payloadBuilder.next();
    }

    /** @return 恒空列表（体征剧本无档位迁移） */
    @Override
    public List<String> drainPhaseTransitions() {
        return List.of();
    }
}
