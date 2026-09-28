package com.fuyun.iotsimulator.scenario;

import java.util.List;

/**
 * 模拟设备剧本引擎接口（P2 PR-2 Task 14）：将既有"固定体征序列循环上报"升级为可切换的
 * 剧本化上行——vitals（既有行为封装）与 infusion（输液状态机剧本）两类实现由
 * {@link ScenarioFactory} 按配置档产出。
 *
 * <p><b>确定性纪律</b>：剧本状态机禁止读取真实时钟，时间流逝一律取自注入的
 * {@code java.time.Clock}（生产传系统时钟、单测注入可手动推进的时钟），保证同一剧本
 * 参数下逐帧内容可回放（联调对账口径）。
 *
 * <p><b>线程安全</b>：实现须保证多线程安全——上报周期线程调 {@link #nextFrame()}，命令
 * 接收线程调 {@link #handleCommand(String, String)}，两线程并发访问剧本内部状态。
 */
public interface Scenario {

    /**
     * 剧本名（与 {@code IOTDA_SCENARIO} 配置档词表一致）。
     *
     * @return 剧本名，非空；vitals / infusion
     */
    String name();

    /**
     * 产出一帧物模型上行 JSON（每个上行周期调用一次）：体征 + 剧本专属指标的
     * Monitor 同服务 properties/report 帧。
     *
     * @return 物模型 JSON 串，非空；形状同 TelemetryPayloadBuilder 产物
     */
    String nextFrame();

    /**
     * 取出并清空待上行的档位公告（剧本阶段迁移时产生，供 DeviceStatusReporter 转 status
     * 属性帧上行）：每档位入口恰好公告一次（如 CRITICAL 的 10ml 橙档与 5ml 红档各一帧）。
     *
     * @return 本周期累计的档位码列表（迁移顺序），非空；无迁移时为空列表
     */
    List<String> drainPhaseTransitions();

    /**
     * 剧本命令回调（P2 PR-2 Task 14，命令下行订阅的上行回执判定源）：命令帧经
     * CommandSubscriber 解析后交由剧本执行。
     *
     * @param commandName 命令名（IoTDA 命令帧 command_name 原值），可空；空值视作不支持
     * @param parasJson   命令参数 JSON 文本（IoTDA 命令帧 paras 原文），可空；无参命令为空串
     * @return true=命令执行成功（回执 result_code=0，平台归 SUCCESS 终态）；
     *         false=剧本不支持或执行失败（回执 result_code=1，平台归 FAILED 终态）
     */
    default boolean handleCommand(String commandName, String parasJson) {
        return false;
    }
}
