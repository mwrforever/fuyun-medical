package com.fuyun.pharmacy.api;

import java.time.Instant;
import java.util.List;

/**
 * 住院医嘱审方通过回执载荷（pharmacy.medication-order.audit-completed，V800 id 53 冻结契约）：
 * 审方工作台通过决策后发布，消费方 M04 驱动医嘱 CREATED→AUDITED（PharmacyAuditReplyListener
 * 读面：target/auditNo/auditOperator/auditedAt 逐字同源）。组件名与 V800 id 53 desc 逐字冻结，
 * 漂移即 CF-6 契约变更须双向评审。
 *
 * @param target        回执定位键=住院医嘱号（m04_order_no），非空
 * @param auditNo       审核方引用=审方任务 id（string 化；M04 order_audit.review_task_no 落值），非空
 * @param auditOperator 审方药师（员工工号 string），非空
 * @param auditedAt     审方通过时点（UTC Instant），非空
 */
public record MedicationAuditCompletedPayload(String target, String auditNo, String auditOperator, Instant auditedAt) {

    /** 顶层组件名清单（契约测试与 V800 id 53 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES = List.of("target", "auditNo", "auditOperator", "auditedAt");
}
