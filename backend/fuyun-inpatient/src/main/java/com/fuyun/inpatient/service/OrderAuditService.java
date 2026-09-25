package com.fuyun.inpatient.service;

import com.fuyun.inpatient.dto.OrderReorganizeRequest;
import com.fuyun.inpatient.enums.OrderStatus;
import java.time.Instant;

/**
 * 医嘱审核与控制服务（FU-M04-05）：开立后系统自动审核（全员必经）+ 药师审方回执驱动迁移
 * （M06 id 53/54）+ 作废/撤回重审/重整/口头医嘱补录确认。停嘱不在本接口（归
 * MedicalOrderService.stop——Task 5 冻结面）；一切状态迁移唯一经 OrderStateMachineService
 * （GC17：迁移即写 order_status_log，调用方不另写留痕）。用药类医嘱 CREATED 停留期语义=
 * 「待药师审」（以 order_audit.stage 区分，不新增状态，04 Spec 红线 2）。
 */
public interface OrderAuditService {

    /**
     * 系统自动审核（开立后全员必经，create/resubmit 审核链收口）：非用药类
     * （order_type 非 DRUG 且非 DISCHARGE_MED）校验通过即 CREATED→AUDITED + order_audit
     * (SYSTEM,PASSED) + 发布 inpatient.order.audited（routing key 携带类型子键，如
     * audited.lab）+ 生效时点落值（begin_at）；用药类系统预检通过后停留 CREATED（语义=
     * 待药师审，order_audit 落 SYSTEM PASSED 行、理由声明待审）等 M06 回执驱动。
     *
     * @param orderNo 医嘱号，非空；来源：开立/驳回重提链
     * @return 审核链收口后医嘱状态（非用药类=AUDITED；用药类=CREATED 停留待审），非空
     * @throws com.fuyun.common.exception.BizException IP-1009（404 医嘱不存在）/IP-1010（409
     *                 状态机违例——非 CREATED 态重复入审）/IP-1022（400 操作者标识非数字）/
     *                 IP-1023（409 order_type 词表外脏数据）
     */
    OrderStatus audit(String orderNo);

    /**
     * 药师审方通过回执（PharmacyAuditReplyListener 消费 V800 id 53 驱动）：CREATED→AUDITED
     * + order_audit(PHARMACIST,PASSED,review_task_no) + 发布 inpatient.order.audited.子键 +
     * begin_at 落值。幂等：医嘱已 AUDITED（重复回执）跳过零副作用；非 CREATED 态由状态机
     * 拒绝（IP-1010 传播进死信留痕——fail-closed）。
     *
     * @param orderNo       医嘱号（回执载荷 target），非空；来源：M06 回执
     * @param reviewTaskNo  审方任务单号（回执载荷 auditNo），可空；来源：M06 回执
     * @param auditOperator 审方药师员工 ID（回执载荷 auditOperator），可空（缺失容错为 0 记录）
     * @param auditedAt     回执审核时点（回执载荷 auditedAt），非空；来源：M06 回执
     * @throws com.fuyun.common.exception.BizException IP-1009（404 医嘱不存在——回执定位失配，
     *                 异常传播进死信留痕）/IP-1010（409 状态机违例）
     */
    void onPharmacistApproved(String orderNo, String reviewTaskNo, String auditOperator, Instant auditedAt);

    /**
     * 药师审方驳回回执（PharmacyAuditReplyListener 消费 V800 id 54 驱动，必附药师意见）：
     * CREATED→AUDIT_REJECTED + order_audit(PHARMACIST,REJECTED,驳回理由) + 发布
     * inpatient.order.audit-rejected（V901 id 67 载荷，医生站修改重提路径）。幂等：医嘱已
     * AUDIT_REJECTED（重复回执）跳过零副作用。
     *
     * @param orderNo       医嘱号（回执载荷 target），非空；来源：M06 回执
     * @param reviewTaskNo  审方任务单号（回执载荷 auditNo），可空；来源：M06 回执
     * @param rejectReason  驳回原因/药师意见（回执载荷 rejectReason，必附），非空；来源：M06 回执
     * @param auditOperator 审方药师员工 ID（回执载荷 auditOperator），可空（缺失容错为 0 记录）
     * @param auditedAt     回执审核时点（回执载荷 auditedAt），非空；来源：M06 回执
     * @throws com.fuyun.common.exception.BizException IP-1009（404 医嘱不存在）/IP-1010（409
     *                 状态机违例——重复驳回回执以外的非 CREATED 态）
     */
    void onPharmacistRejected(
            String orderNo, String reviewTaskNo, String rejectReason, String auditOperator, Instant auditedAt);

    /**
     * 医嘱作废（POST /orders/{no}/cancel）：仅未产生执行——AUDITED/TRANSFERRED→CANCELLED
     * （状态机裁决，EXECUTING 起拒 IP-1010）+ 发布 inpatient.order.cancelled（V800 id 45
     * 载荷；M05 撮此撤销未执行执行单并拦截在途核对，执行单撤销细节归 M05/PR-3）。
     *
     * @param orderNo 医嘱号，非空；来源：路径参数
     * @param reason  作废原因，非空；来源：作废端点入参
     * @throws com.fuyun.common.exception.BizException IP-1009（404 医嘱不存在）/IP-1010（409
     *                 已产生执行不可作废——状态机违例）/IP-1022（400 操作者标识非数字）
     */
    void cancel(String orderNo, String reason);

    /**
     * 撤回审核（POST /orders/{no}/revoke-audit）：仅转抄前——AUDITED→CREATED（撤回重审；
     * TRANSFERRED 起已有转抄执行面拒 IP-1010）+ 生效时点清空（begin_at 复位）+ 发布
     * inpatient.order.revoked（V800 id 46 载荷，无订阅方登记保证契约完整）。
     *
     * @param orderNo 医嘱号，非空；来源：路径参数
     * @throws com.fuyun.common.exception.BizException IP-1009（404 医嘱不存在）/IP-1010（409
     *                 已转抄不可撤回——状态机违例）/IP-1022（400 操作者标识非数字）
     */
    void revokeAudit(String orderNo);

    /**
     * 医嘱重整（POST /orders/reorganize）：只重排视图序并留痕——每条医嘱落一行
     * order_status_log（from=to，reason=医嘱重整；列表顺序即新视图序），<b>不迁移任何状态、
     * 不改医嘱内容</b>（04 Spec §3.3 冻结语义；重整单视图取数以留痕行为锚）。
     *
     * @param req 重整入参（就诊号+新视图序医嘱号列表），非空；来源：重整端点
     * @throws com.fuyun.common.exception.BizException IP-1007（404 就诊不存在）/IP-1009（404
     *                 医嘱不存在）/IP-1022（400 操作者标识非数字）/IP-1023（409 医嘱归属
     *                 其他就诊——归属不符）
     */
    void reorganize(OrderReorganizeRequest req);

    /**
     * 抢救口头医嘱补录确认（POST /orders/{no}/oral-confirm）：oral_flag 明细行标记的确认
     * 收口——oral_confirmed_at 落值（服务器时间；V905 增列）；重复确认与非口头医嘱确认拒
     * IP-1010。状态不迁移（补录确认为审计动作非状态迁移；限时催办归 P3）。
     *
     * @param orderNo 医嘱号，非空；来源：路径参数
     * @throws com.fuyun.common.exception.BizException IP-1009（404 医嘱不存在）/IP-1010（409
     *                 非抢救口头医嘱或已确认——补录确认面违例）/IP-1023（409 并发确认窗口）
     */
    void oralConfirm(String orderNo);
}
