package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 医嘱转抄事件载荷（inpatient.order.transferred，V800 id 42 冻结契约）：护士转抄核对完成
 * （AUDITED→TRANSFERRED）发布；M05 据此生成临时医嘱单次执行单。transferred 登记名不带
 * routing 子键（R3-06——仅 order.created/audited 携子键），类型经载荷 transferType 承载
 * （子键小写形态，与 OrderType.subKey 同源）。脱敏红线：仅定位键与时间线，禁患者姓名/诊断文本。
 *
 * @param m04OrderNo        医嘱号，非空；来源：转抄医嘱业务号
 * @param visitId           住院就诊号（I 型 14 位），非空；来源：医嘱关联就诊
 * @param patientId         患者主索引，非空
 * @param transferType      转抄医嘱类型（OrderType 子键小写形态：drug/lab/exam/surgery/blood/nursing/diet/consult/discharge-med——M05 消费侧类型分发锚），非空
 * @param firstTransferredAt 首次转抄时点（UTC，服务器时间），非空；与 order_transfer_log.transferred_at 同源
 */
public record OrderTransferredPayload(
        String m04OrderNo, String visitId, long patientId, String transferType, Instant firstTransferredAt) {}
