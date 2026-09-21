package com.fuyun.outpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.billing.api.RefundApprovedPayload;
import com.fuyun.billing.api.SettlementCompletedPayload;
import com.fuyun.billing.api.SettlementQueryPort;
import com.fuyun.billing.api.SettlementSourceRefs;
import com.fuyun.outpatient.api.OrderCancelledPayload;
import com.fuyun.outpatient.api.OrderChargedPayload;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.entity.ClinicOrder;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.entity.VisitStatusLog;
import com.fuyun.outpatient.enums.OrderStatus;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.internal.OutpatientDomainEvent;
import com.fuyun.outpatient.mapper.ClinicOrderMapper;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.outpatient.mapper.VisitStatusLogMapper;
import com.fuyun.outpatient.service.IAppointmentService;
import com.fuyun.outpatient.service.IChargingService;
import com.fuyun.outpatient.service.OutpatientVisitStateMachine;
import com.fuyun.pharmacy.api.PrescriptionCancelledPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/**
 * 门诊收费编排服务实现（M03 FU-M03-07/08，Task 10）：settlement.completed 单据精确放行——
 * 以 billing SettlementQueryPort 反查的 orderRefs/rxRefs 为唯一放行清单（单据精确，禁 visit
 * 全量扫描，避免误放未纳入本结算的单据），申请单 PENDING_FEE→CHARGED+fee_settlement_id 回填、
 * 处方引用行 CREATED→CHARGED（药品费用行不经 fee.created 推进，引用行停留 CREATED，Spec :142/
 * FU-M03-08），有放行增量才扇出 order.charged（重投幂等二次零发布）；refund.approved order
 * 分支回滚（CHARGED→CANCELLED 逆向+order.cancelled 逐单扇出，回执驱动逆向仅在回执后，Spec :119；
 * appointment 分支终态归 IAppointmentService.confirmRefundedCancel）；prescription.cancelled
 * 回流引用行作废（三态 CAS）、dispense 回执「已发药」派生镜像（状态机五值不变）。资金无涉红线
 * （裁决 7）：零金额逻辑。消费线程 ThreadLocal 无操作者上下文，留痕一律 {@link #SYSTEM_OPERATOR}
 * 哨兵（Task 6/8 先例）。重投幂等口径=CAS 0 行重读定性跳过。线程安全：无状态单例。
 * 装配归 OutpatientWebConfig @Import；com.fuyun.outpatient.service.impl 包 = JaCoCo PACKAGE
 * LINE 1.00 覆盖对象。
 */
@Slf4j
public class ChargingServiceImpl implements IChargingService {

    /** 门诊结算类型 code（settleType 守卫：仅 OUT 进编排；IN 为住院分支忽略） */
    private static final String SETTLE_TYPE_OUT = "OUT";

    /** 退费逆向扇出 reason（OrderCancelledPayload 终态确认冻结文案） */
    private static final String REFUND_CANCEL_REASON = "退费逆向终态确认";

    /** 发药回流镜像态：已发药（V203 dispense_status 词表） */
    private static final String MIRROR_DISPENSED = "DISPENSED";

    /** 发药回流镜像态：部分退药 */
    private static final String MIRROR_PART_RETURNED = "PART_RETURNED";

    /** 发药回流镜像态：整单退药 */
    private static final String MIRROR_FULL_RETURNED = "FULL_RETURNED";

    /**
     * 系统定性动作操作者哨兵（Task 6/8 先例）：消费运行于 MQ 消费线程——ThreadLocal 操作者上下文
     * 不跨线程（backend 宪法 A.1-10），visit_status_log.operator 取本哨兵。
     */
    private static final String SYSTEM_OPERATOR = "system";

    private final ClinicOrderMapper clinicOrderMapper;

    private final VisitMapper visitMapper;

    private final VisitStatusLogMapper visitStatusLogMapper;

    private final SettlementQueryPort settlementQueryPort;

    private final IAppointmentService appointmentService;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import，backend 宪法 B.1）。
     *
     * @param clinicOrderMapper    申请单主单 mapper，非空；放行/回滚/回流 CAS
     * @param visitMapper          就诊记录 mapper，非空；visit 存在性校验与回诊推进 CAS
     * @param visitStatusLogMapper 迁移日志 mapper，非空；红线 5 每迁必记（PENDING_FEE→IN_CONSULT）
     * @param settlementQueryPort  结算单反查端口（billing api 契约），非空；单据精确清单唯一来源
     * @param appointmentService   预约服务，非空；挂号费收费回填分发（visit 锚定位+PAID+fee_settlement_id 回填）
     * @param events               Spring 应用事件发布器，非空；事务内发布 order.charged/cancelled
     */
    public ChargingServiceImpl(
            ClinicOrderMapper clinicOrderMapper,
            VisitMapper visitMapper,
            VisitStatusLogMapper visitStatusLogMapper,
            SettlementQueryPort settlementQueryPort,
            IAppointmentService appointmentService,
            ApplicationEventPublisher events) {
        this.clinicOrderMapper = clinicOrderMapper;
        this.visitMapper = visitMapper;
        this.visitStatusLogMapper = visitStatusLogMapper;
        this.settlementQueryPort = settlementQueryPort;
        this.appointmentService = appointmentService;
        this.events = events;
    }

    /**
     * 结算完成消费业务（接口 javadoc 契约）：类型守卫→锚守卫→0 元拒绝→visit 校验→端口反查→
     * 放行 CAS→扇出→回诊推进→挂号费回填分发，同一事务原子。
     *
     * @param payload 结算完成载荷，非空
     * @throws IllegalStateException 载荷/数据异常与端口异常（转译）时触发——死信留痕
     */
    @Override
    @Transactional
    public void onSettlementCompleted(SettlementCompletedPayload payload) {
        // OUT/门诊结算类型守卫：IN 为住院分支忽略（其余结算类型同口径不属门诊放行编排），info 跳过
        if (!SETTLE_TYPE_OUT.equals(payload.settleType())) {
            log.info("结算完成非门诊类型，跳过（IN 为住院分支）：settleType={}，settleNo={}", payload.settleType(), payload.settleNo());
            return;
        }
        requireSettlementAnchors(payload);
        // 0 元/负额结算拒绝（W-20 两问澄清前沿用既有 400 拒绝语义，不放开不收窄；缺失值落 0 同口径）
        if (payload.totalAmount() == null || payload.totalAmount() <= 0) {
            throw new IllegalStateException("结算完成载荷金额违例（0 元/负额结算拒绝，W-20 澄清前维持拒绝口径）：settleNo=" + payload.settleNo()
                    + "，totalAmount=" + payload.totalAmount());
        }
        // ① visit 存在性校验（就诊记录缺失属数据异常，死信留痕人工对账）
        Visit visit = requireVisit(payload.visitId());
        // ② 单据精确清单反查（端口异常转译可读拒绝，W-20 关联面红线）
        SettlementSourceRefs refs = requireSourceRefs(payload.settlementId());
        // ② 申请单放行 CAS（PENDING_FEE→CHARGED+结算锚回填）：逐单以反查清单为准，禁 visit 全量扫描
        int chargedOrders = 0;
        for (String orderNo : refs.orderRefs()) {
            // 数据库写操作：单条原子放行+结算锚回填；0 行=重投/竞态，重读定性（CHARGED 幂等 info）
            if (clinicOrderMapper.casCharge(orderNo, payload.settlementId()) == 1) {
                chargedOrders++;
                continue;
            }
            ClinicOrder latest = clinicOrderMapper.selectOne(
                    Wrappers.<ClinicOrder>lambdaQuery().eq(ClinicOrder::getOrderNo, orderNo));
            OrderStatus current = latest == null ? null : latest.getStatus();
            if (current == OrderStatus.CHARGED) {
                log.info("单据放行幂等跳过（已缴费）：orderNo={}，settleNo={}", orderNo, payload.settleNo());
            } else {
                log.warn("单据放行跳过（当前态 {} 非待缴费，费用↔单据对账兜底）：orderNo={}，settleNo={}", current, orderNo, payload.settleNo());
            }
        }
        // ②b 处方引用行 CREATED→CHARGED（与②同事务并列；药品费用行不经 fee.created 推进故自 CREATED 直迁）
        int chargedRxRefs = 0;
        for (String rxNo : refs.rxRefs()) {
            // 数据库写操作：引用行按 ext_ref 精确 CAS；0 行=重投/竞态，重读定性
            if (clinicOrderMapper.casRxRefCharged(rxNo) == 1) {
                chargedRxRefs++;
                continue;
            }
            ClinicOrder latest = clinicOrderMapper.selectOne(
                    Wrappers.<ClinicOrder>lambdaQuery().eq(ClinicOrder::getExtRef, rxNo));
            OrderStatus current = latest == null ? null : latest.getStatus();
            if (current == OrderStatus.CHARGED) {
                log.info("处方引用行放行幂等跳过（已缴费）：rxNo={}，settleNo={}", rxNo, payload.settleNo());
            } else {
                log.warn("处方引用行放行跳过（当前态 {} 非已开立）：rxNo={}，settleNo={}", current, rxNo, payload.settleNo());
            }
        }
        // ③④ order.charged 扇出（有放行增量才发布——重投幂等第二次全跳过零发布；清单=端口反查全集逐字透传）
        if (chargedOrders > 0 || chargedRxRefs > 0) {
            events.publishEvent(new OutpatientDomainEvent(
                    OutpatientMessagingConstants.EVENT_ORDER_CHARGED,
                    new OrderChargedPayload(
                            payload.settlementId(),
                            payload.settleNo(),
                            payload.patientId(),
                            payload.visitId(),
                            refs.orderRefs(),
                            refs.rxRefs(),
                            false)));
        }
        // ⑤ visit 状态推进（PENDING_FEE→IN_CONSULT 合法迁移对，状态机单点+每迁必记；他态为多单并推/诊毕正常竞态）
        advanceVisitToInConsult(visit);
        // ⑥ 挂号费收费回填分发（挂号费与就诊费同 visit 结算面——PAID⇒visit 锚在位，Task 6 契约缝定案①落点）
        appointmentService.markRegistrationPaid(payload.settleNo(), payload.settlementId(), payload.visitId());
        log.info(
                "结算完成消费处理完毕：settleNo={}，settlementId={}，visitId={}，chargedOrders={}，chargedRxRefs={}",
                payload.settleNo(),
                payload.settlementId(),
                payload.visitId(),
                chargedOrders,
                chargedRxRefs);
    }

    /**
     * 退费回执 order 分支（接口 javadoc 契约）：反查清单逐单 CHARGED→CANCELLED 逆向，CAS 命中才
     * 逐单扇出 order.cancelled（无命中/非 CHARGED/并发落败零发布，幂等收敛）。
     *
     * @param payload 退费回执载荷，非空
     * @throws IllegalStateException 端口反查异常（转译）时触发——死信留痕
     */
    @Override
    @Transactional
    public void onRefundApproved(RefundApprovedPayload payload) {
        SettlementSourceRefs refs = requireSourceRefs(payload.settlementId());
        for (String orderNo : refs.orderRefs()) {
            // 数据库读操作：按单号定位本域申请单（退号链/非 M03 开单等无命中即幂等跳过）
            ClinicOrder order = clinicOrderMapper.selectOne(
                    Wrappers.<ClinicOrder>lambdaQuery().eq(ClinicOrder::getOrderNo, orderNo));
            if (order == null) {
                log.info(
                        "退费逆向幂等跳过（无本域单据命中）：orderNo={}，settlementId={}，refundNo={}",
                        orderNo,
                        payload.settlementId(),
                        payload.refundNo());
                continue;
            }
            // 逆向仅在回执后的 CHARGED 态（Spec :119）；CREATED/PENDING_FEE 属作废链已处置，重投已 CANCELLED
            if (order.getStatus() != OrderStatus.CHARGED) {
                log.info(
                        "退费逆向跳过（当前态 {} 非已缴费，逆向仅在回执后 CHARGED 态）：orderNo={}，refundNo={}",
                        order.getStatus(),
                        orderNo,
                        payload.refundNo());
                continue;
            }
            // 数据库写操作：CHARGED→CANCELLED 逆向 CAS；0 行=并发已迁移，warn 跳过零发布
            if (clinicOrderMapper.casStatus(
                            order.getId(), OrderStatus.CHARGED.getCode(), OrderStatus.CANCELLED.getCode())
                    == 0) {
                log.warn("退费逆向 CAS 落败（并发已迁移）：orderNo={}，refundNo={}", orderNo, payload.refundNo());
                continue;
            }
            order.setStatus(OrderStatus.CANCELLED);
            // 逐单发布 order.cancelled（终态确认；rxNos=结算单处方清单，M06 未发药作废/退药收敛据此，本模块不直改 pharmacy 表）
            events.publishEvent(new OutpatientDomainEvent(
                    OutpatientMessagingConstants.EVENT_ORDER_CANCELLED,
                    new OrderCancelledPayload(
                            orderNo, order.getPatientId(), order.getVisitId(), refs.rxRefs(), REFUND_CANCEL_REASON)));
            log.info(
                    "退费逆向单据收敛完成：orderNo={}，visitId={}，refundNo={}，settlementId={}",
                    orderNo,
                    order.getVisitId(),
                    payload.refundNo(),
                    payload.settlementId());
        }
    }

    /**
     * 处方作废回流（接口 javadoc 契约）：RX_REF 引用行三态 CAS 至 CANCELLED（Spec :119 R2-10）。
     *
     * @param payload 处方作废载荷，非空
     * @throws IllegalStateException 载荷缺 rxNo 锚时触发——死信留痕
     */
    @Override
    @Transactional
    public void onPrescriptionCancelled(PrescriptionCancelledPayload payload) {
        requireRxRef(payload.rxNo(), "pharmacy.prescription.cancelled", payload.prescriptionId());
        // 数据库写操作：引用行按 ext_ref 三态 CAS 至 CANCELLED（0 行=重投幂等/引用行缺失）
        int updated = clinicOrderMapper.casCancelRxRef(payload.rxNo());
        if (updated == 0) {
            log.info("处方作废回流幂等跳过（无在迁 RX_REF 行命中）：rxNo={}，prescriptionId={}", payload.rxNo(), payload.prescriptionId());
        } else {
            log.info("处方作废回流联动完成：rxNo={}，联动行数={}，visitId={}", payload.rxNo(), updated, payload.visitId());
        }
    }

    /**
     * 发药完成回流（接口 javadoc 契约）：RX_REF 引用行镜像写 DISPENSED（空档 CAS）。
     *
     * @param rxNo       处方号，非空
     * @param dispenseNo 发药单号，非空
     * @throws IllegalStateException rxNo 空白时触发——死信留痕
     */
    @Override
    @Transactional
    public void onDispenseCompleted(String rxNo, String dispenseNo) {
        requireRxRef(rxNo, "pharmacy.dispense.completed", dispenseNo);
        // 数据库写操作：镜像空档 CAS（乱序投递下退药镜像先至时不被完成回执覆盖）
        if (clinicOrderMapper.casMirrorDispensed(rxNo) == 1) {
            log.info("已发药镜像回流完成：rxNo={}，dispenseNo={}", rxNo, dispenseNo);
        } else {
            log.info("已发药镜像幂等跳过（非空档，重投/退药镜像先至）：rxNo={}，dispenseNo={}", rxNo, dispenseNo);
        }
    }

    /**
     * 退药受理回流（接口 javadoc 契约）：镜像按 fullReturn 迁 PART_RETURNED/FULL_RETURNED，
     * 单调不回退。
     *
     * @param rxNo       处方号，非空
     * @param fullReturn 是否整单退药
     * @param dispenseNo 发药单号，非空
     * @throws IllegalStateException rxNo 空白时触发——死信留痕
     */
    @Override
    @Transactional
    public void onDispenseReturned(String rxNo, boolean fullReturn, String dispenseNo) {
        requireRxRef(rxNo, "pharmacy.dispense.returned", dispenseNo);
        // 数据库写操作：镜像单调 CAS（fullReturn 载荷组件定目标态；FULL_RETURNED 后零回写）
        String mirror = fullReturn ? MIRROR_FULL_RETURNED : MIRROR_PART_RETURNED;
        if (clinicOrderMapper.casMirrorReturned(rxNo, mirror) == 1) {
            log.info("退药镜像回流完成：rxNo={}，mirror={}，dispenseNo={}", rxNo, mirror, dispenseNo);
        } else {
            log.info("退药镜像幂等跳过（同值重投/整单终态后回写）：rxNo={}，mirror={}，dispenseNo={}", rxNo, mirror, dispenseNo);
        }
    }

    // ---------------------------------------------------------------- 私有辅助

    /**
     * 结算载荷锚守卫（settlementId/settleNo/visitId/patientId 缺失即不合规帧）：可读可定位拒绝进
     * 死信（禁静默消费——结算回执是放行/回填的唯一权威）。
     *
     * @param payload 结算完成载荷，非空
     * @throws IllegalStateException 任一锚缺失时触发
     */
    private static void requireSettlementAnchors(SettlementCompletedPayload payload) {
        if (payload.settlementId() == null
                || payload.settlementId() == 0L
                || payload.settleNo() == null
                || payload.settleNo().isBlank()
                || payload.visitId() == null
                || payload.visitId().isBlank()
                || payload.patientId() == null
                || payload.patientId() == 0L) {
            throw new IllegalStateException("结算完成载荷不合规（缺 settlementId/settleNo/visitId/patientId 锚）：settleNo="
                    + payload.settleNo() + "，settlementId=" + payload.settlementId() + "，visitId=" + payload.visitId());
        }
    }

    /**
     * 回执 rxNo 锚守卫（pharmacy 回流三事件共用）：缺失即不合规帧可读拒绝。
     *
     * @param rxNo      处方号载荷值
     * @param eventType 事件类型（定位留痕）
     * @param bizNo     伴随业务号（dispenseNo/prescriptionId，定位留痕）
     * @throws IllegalStateException rxNo 空白时触发
     */
    private static void requireRxRef(String rxNo, String eventType, String bizNo) {
        if (rxNo == null || rxNo.isBlank()) {
            throw new IllegalStateException("回流载荷不合规（缺 rxNo 锚）：" + eventType + "，bizNo=" + bizNo);
        }
    }

    /**
     * visit 存在性校验（结算回执就诊记录缺失属数据异常）。
     *
     * @param visitId CF-3 就诊号，非空
     * @return visit 实体，非空
     * @throws IllegalStateException visit 缺失时触发（死信留痕人工对账）
     */
    private Visit requireVisit(String visitId) {
        // 数据库读操作：就诊号定位就诊记录
        Visit visit = visitMapper.selectOne(Wrappers.<Visit>lambdaQuery().eq(Visit::getVisitId, visitId));
        if (visit == null) {
            throw new IllegalStateException("结算完成消费失败：就诊记录缺失（数据异常，人工对账）：visitId=" + visitId);
        }
        return visit;
    }

    /**
     * 结算单反查（端口异常一律转译为可读拒绝——W-20 关联面红线，禁裸抛底层异常）。
     *
     * @param settlementId 结算单 id，非空非零
     * @return 来源单据引用组，非空
     * @throws IllegalStateException 端口调用异常时触发（消费事务回滚，死信留痕对账）
     */
    private SettlementSourceRefs requireSourceRefs(long settlementId) {
        try {
            return settlementQueryPort.sourceRefsOfSettlement(settlementId);
        } catch (RuntimeException e) {
            log.error("结算单反查端口调用异常，拒绝消费编排进死信对账：settlementId={}", settlementId, e);
            throw new IllegalStateException("结算单反查失败（M13 端口异常），拒绝放行/回滚编排：settlementId=" + settlementId);
        }
    }

    /**
     * visit 回诊推进（仅 PENDING_FEE 迁 IN_CONSULT，红线 5 状态机单点+每迁必记）：他态（已回诊/
     * 已诊毕/已回滚）为多单并推与诊毕竞态的正常态，跳过不报错；CAS 并发落败 warn 跳过。
     *
     * @param visit 已过存在性校验的就诊记录，非空
     */
    private void advanceVisitToInConsult(Visit visit) {
        if (visit.getStatus() != VisitStatus.PENDING_FEE) {
            log.info(
                    "visit 非待缴费态，跳过回诊推进：visitId={}，status={}",
                    visit.getVisitId(),
                    visit.getStatus().getCode());
            return;
        }
        OutpatientVisitStateMachine.require(VisitStatus.PENDING_FEE.getCode(), VisitStatus.IN_CONSULT.getCode());
        // 数据库写操作：visit CAS PENDING_FEE→IN_CONSULT；命中后每迁必记（红线 5）
        if (visitMapper.casStatus(visit.getId(), VisitStatus.PENDING_FEE.getCode(), VisitStatus.IN_CONSULT.getCode())
                == 1) {
            VisitStatusLog statusLog = new VisitStatusLog();
            statusLog.setVisitId(visit.getVisitId());
            statusLog.setFromStatus(VisitStatus.PENDING_FEE);
            statusLog.setToStatus(VisitStatus.IN_CONSULT);
            statusLog.setReason("缴费完成回执回诊推进");
            statusLog.setOperator(SYSTEM_OPERATOR);
            // 数据库写操作：迁移日志每迁必记（红线 5）
            visitStatusLogMapper.insert(statusLog);
        } else {
            log.warn("visit 回诊推进 CAS 落败跳过（并发已迁移）：visitId={}", visit.getVisitId());
        }
    }
}
