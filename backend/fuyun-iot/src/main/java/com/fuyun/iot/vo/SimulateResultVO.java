package com.fuyun.iot.vo;

import com.fuyun.iot.enums.AlarmLevel;
import java.time.Instant;
import java.util.List;

/**
 * 告警模拟结果对象（POST /api/v1/iot/alarm-rules/{id}/simulate 出网载体）：历史回放评估的
 * 触发明细（不落库，仅评估视图）。
 *
 * @param scannedRows 回放窗口内参与评估的遥测行数，非空
 * @param triggers    触发明细清单（按触发时点升序），非空；无触发为空清单
 */
public record SimulateResultVO(long scannedRows, List<SimulateTrigger> triggers) {

    /**
     * 单条触发明细（回放状态机的触发时刻快照）。
     *
     * @param deviceId     触发设备号，非空
     * @param metricCode   指标编码（=规则指标编码），非空
     * @param triggeredAt  触发时点（持续时长达标时刻，UTC），非空
     * @param triggerValue 触发值原文，非空
     * @param alarmLevel   将产生的告警级别（规则级别），非空
     */
    public record SimulateTrigger(
            String deviceId, String metricCode, Instant triggeredAt, String triggerValue, AlarmLevel alarmLevel) {}
}
