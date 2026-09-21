package com.fuyun.outpatient.api;

import java.util.List;

/**
 * 门诊退费逆向扇出事件载荷（outpatient.order.cancelled，V204 id 31 冻结契约——V702 占位经 UPDATE 升级正式载荷）：
 * 退费逆向终态发布，本模块承担未发药作废与退药单终态确认（06-pharmacy §8 B-3 单向链），
 * M07/M08/M05 随 P3。
 *
 * @param orderNo   申请单业务号（order_no，逆向主锚点）
 * @param patientId 患者主索引
 * @param visitId   CF-3 门诊就诊号
 * @param rxNos     退费逆向涉及的处方号清单（终态确认清单——经 billing SettlementQueryPort 按
 *                  settlementId 反查，M06 未发药处方作废/已退药单据收敛以此为准）
 * @param reason    退费原因（业务留痕，脱敏后承载）
 */
public record OrderCancelledPayload(String orderNo, Long patientId, String visitId, List<String> rxNos, String reason) {

    /** 顶层组件名清单（契约测试与 V204 id 31 UPDATE 后 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES = List.of("orderNo", "patientId", "visitId", "rxNos", "reason");
}
