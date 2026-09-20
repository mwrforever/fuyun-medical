package com.fuyun.outpatient.api;

import java.util.List;

/**
 * 退号完成事件载荷（outpatient.appointment.cancelled，V204 id 37 冻结契约）：
 * 发布时点=号源已回池、退费联动已触发，M18 患者端订单同步依据（订阅随 P2）。
 *
 * @param apptNo            预约单业务号
 * @param patientId         患者主索引
 * @param reason            退号原因（业务留痕，脱敏后承载）
 * @param feeRefundTriggered 退费联动是否已触发（true=已过支付时限走退费；false=支付时限内免退费路径）
 */
public record AppointmentCancelledPayload(String apptNo, Long patientId, String reason, boolean feeRefundTriggered) {

    /** 顶层组件名清单（契约测试与 V204 id 37 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES = List.of("apptNo", "patientId", "reason", "feeRefundTriggered");
}
