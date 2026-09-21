package com.fuyun.outpatient.api;

import java.util.List;

/**
 * 门诊诊毕事件载荷（outpatient.visit.finished，V204 id 33 冻结契约）：
 * 诊毕状态迁移同事务发布，M09 信息页/病案与 M19 工作量统计取数依据（订阅随 P4）。
 *
 * @param visitId        CF-3 门诊就诊号
 * @param patientId      患者主索引
 * @param disposition    离院去向（国标代码 1~7/9，词表校验见 OP-1018）
 * @param finishOperator 诊毕操作者（员工 id 或 PORTAL 哨兵，审计留痕锚点）
 */
public record VisitFinishedPayload(String visitId, Long patientId, String disposition, String finishOperator) {

    /** 顶层组件名清单（契约测试与 V204 id 33 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES = List.of("visitId", "patientId", "disposition", "finishOperator");
}
