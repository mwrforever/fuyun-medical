package com.fuyun.iot.api.payload;

import java.time.Instant;

/**
 * 联动执行事件载荷（iot.linkage.executed，V1004 id 80 冻结契约）：联动规则命中并执行动作后发布，
 * M05/M16 据此留痕联动结果与闭环提示。
 *
 * @param linkageNo     联动执行业务号，非空；来源：联动执行域签发
 * @param ruleId        命中联动规则 ID，非空；来源：linkage_rule 表主键
 * @param triggerSource 触发来源类型，非空（遥测指标/告警/命令回推等联动触发源分类）；来源：
 *                      联动引擎触发判定
 * @param triggerRef    触发来源引用，非空（如触发告警的 alarmNo）；来源：触发判定上下文
 * @param actionType    动作类型，非空（播报/命令下发/通知等联动动作分类）；来源：命中规则动作配置
 * @param actionResult  动作执行结果，非空（成功/失败终态）；来源：动作执行器回执
 * @param executedAt    执行时点（UTC），非空；来源：动作执行完成时刻
 */
public record LinkageExecutedPayload(
        String linkageNo,
        Long ruleId,
        String triggerSource,
        String triggerRef,
        String actionType,
        String actionResult,
        Instant executedAt) {}
