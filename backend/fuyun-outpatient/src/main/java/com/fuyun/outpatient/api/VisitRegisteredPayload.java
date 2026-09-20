package com.fuyun.outpatient.api;

import java.util.List;

/**
 * 门诊挂号/取号成功事件载荷（outpatient.visit.registered，V204 id 32 冻结契约）：
 * 挂号/取号落库同事务发布，M13（本仓 billing）医保就诊登记依据（Spec :160，订阅随 P3）。
 *
 * @param visitId   CF-3 门诊就诊号（O+yyyyMMdd+5 位流水，M03 唯一签发）
 * @param patientId 患者主索引
 * @param visitType 就诊类型（普通/急诊/专家等业务代码）
 * @param deptCode  开诊科室编码（候诊队列与统计维度锚点）
 * @param doctorId  接诊医生 id（约诊挂号有值；普通挂号按排班回填）
 */
public record VisitRegisteredPayload(
        String visitId, Long patientId, String visitType, String deptCode, String doctorId) {

    /** 顶层组件名清单（契约测试与 V204 id 32 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES =
            List.of("visitId", "patientId", "visitType", "deptCode", "doctorId");
}
