package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 医嘱停止事件载荷（inpatient.order.stopped，V800 id 44 冻结契约）：医生停嘱与转科自动
 * 停嘱共用（stop/stopAllForTransfer 共用实现同源发布）；M05 撮此撤销未执行执行单、M13
 * 按停嘱时点截断持续性费用。脱敏红线：仅定位键与时间线/原因，禁患者姓名/诊断文本。
 *
 * @param m04OrderNo  医嘱号，非空；来源：停嘱医嘱业务号
 * @param visitId     住院就诊号（I 型 14 位），非空；来源：停嘱医嘱关联就诊
 * @param patientId   患者主索引，非空
 * @param stoppedAt   停嘱时点（UTC，服务器时间），非空；M13 费用截断时间线依据
 * @param stopOperator 停嘱操作者（M01 用户标识），非空；转科路径为编排操作者
 * @param stopReason  停嘱原因（转科固定文案「转科」/医生停嘱理由），非空
 */
public record OrderStoppedPayload(
        String m04OrderNo, String visitId, long patientId, Instant stoppedAt, String stopOperator, String stopReason) {}
