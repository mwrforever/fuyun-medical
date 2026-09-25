package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 医嘱撤回事件载荷（inpatient.order.revoked，V800 id 46 冻结契约）：撤回仅转抄前
 * （AUDITED→CREATED 撤回重审；TRANSFERRED 起已有执行单禁撤）；M05 不订阅（转抄前无执行单，
 * R3-07 死订阅已删），登记保证契约完整。脱敏红线：仅定位键与时间线，禁患者姓名/诊断文本。
 *
 * @param m04OrderNo 医嘱号，非空；来源：撤回医嘱业务号
 * @param visitId    住院就诊号（I 型 14 位），非空；来源：撤回医嘱关联就诊
 * @param patientId  患者主索引，非空
 * @param revokedAt  撤回时点（UTC，服务器时间），非空
 */
public record OrderRevokedPayload(String m04OrderNo, String visitId, long patientId, Instant revokedAt) {}
