package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 医嘱作废事件载荷（inpatient.order.cancelled，V800 id 45 冻结契约）：仅未产生执行的医嘱
 * 可作废（AUDITED/TRANSFERRED 态；执行单撤销归 M05 消费本事件实现）；M05 撮此撤销未执行
 * 执行单并拦截在途核对。脱敏红线：仅定位键与时间线/原因，禁患者姓名/诊断文本。
 *
 * @param m04OrderNo   医嘱号，非空；来源：作废医嘱业务号
 * @param visitId      住院就诊号（I 型 14 位），非空；来源：作废医嘱关联就诊
 * @param patientId    患者主索引，非空
 * @param cancelledAt  作废时点（UTC，服务器时间），非空
 * @param cancelReason 作废原因（医生作废理由），非空
 */
public record OrderCancelledPayload(
        String m04OrderNo, String visitId, long patientId, Instant cancelledAt, String cancelReason) {}
