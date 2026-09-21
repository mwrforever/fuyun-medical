package com.fuyun.outpatient.api;

import java.util.List;

/**
 * 门诊退号回滚事件载荷（outpatient.visit.cancelled，V204 id 34 冻结契约）：
 * 退号回滚同事务发布，M13 就诊登记撤销与 M18 患者端同步依据（订阅随 P3/P2）。
 *
 * @param visitId   CF-3 门诊就诊号
 * @param patientId 患者主索引
 * @param reason    退号原因（业务留痕，脱敏后承载）
 */
public record VisitCancelledPayload(String visitId, Long patientId, String reason) {

    /** 顶层组件名清单（契约测试与 V204 id 34 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES = List.of("visitId", "patientId", "reason");
}
