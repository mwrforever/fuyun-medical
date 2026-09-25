package com.fuyun.inpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.BillingAccountQueryPort;
import com.fuyun.billing.api.DischargePrecheckView;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.OrderAuditedPayload;
import com.fuyun.inpatient.api.payload.VisitDischargeRequestedPayload;
import com.fuyun.inpatient.api.payload.VisitDischargedPayload;
import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.DischargeConfirmRequest;
import com.fuyun.inpatient.dto.DischargeRequestCreate;
import com.fuyun.inpatient.entity.DischargeRequest;
import com.fuyun.inpatient.entity.FollowUpPlan;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.entity.MedicalOrder;
import com.fuyun.inpatient.entity.OrderAudit;
import com.fuyun.inpatient.entity.OrderExecutePlan;
import com.fuyun.inpatient.enums.AuditStage;
import com.fuyun.inpatient.enums.DischargeRequestStatus;
import com.fuyun.inpatient.enums.DischargeWay;
import com.fuyun.inpatient.enums.FollowUpStatus;
import com.fuyun.inpatient.enums.OrderClass;
import com.fuyun.inpatient.enums.OrderStatus;
import com.fuyun.inpatient.enums.OrderType;
import com.fuyun.inpatient.enums.PlanStatus;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.DischargeRequestMapper;
import com.fuyun.inpatient.mapper.FollowUpPlanMapper;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.mapper.MedicalOrderMapper;
import com.fuyun.inpatient.mapper.OrderAuditMapper;
import com.fuyun.inpatient.mapper.OrderExecutePlanMapper;
import com.fuyun.inpatient.service.BedService;
import com.fuyun.inpatient.service.DischargeService;
import com.fuyun.inpatient.service.MedicalOrderService;
import com.fuyun.inpatient.service.OrderStateMachineService;
import com.fuyun.inpatient.vo.ClearanceVO;
import com.fuyun.inpatient.vo.DischargeRequestVO;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出院管理域服务实现（FU-M04-07，V907 两表业务面）。出院申请=单事务在途清理编排
 * （①长期医嘱批量停嘱——复用 MedicalOrderService.stopAllForTransfer 停嘱面[reason=出院]，
 * 状态机唯一裁决不变；②无合法停嘱边的停留医嘱[CREATED/AUDIT_REJECTED，04 Spec §3.3 无
 * 该停嘱边]与临时在途医嘱逐条入清理结果追踪清单供人工处置——不迁移状态；③未执行计划全量
 * 作废——复用 OrderExecutePlanMapper.cancelPendingByOrderIds 条件更新面，GC19 在途计划清零
 * 在申请时点达成）+ 费用预审（BillingAccountQueryPort 只读快照：结清 READY/欠费 BLOCKED
 * 附欠费额=max(0,未结清-押金余额)——GC18 金额零落地，仅存权威数据快照回显）。离院确认
 * =GC19 三重前置校验（全部长期医嘱终态+在途计划清零+预审 READY 且结算标记双条件，任一
 * 不满足抛 IP-1017）后置 DISCHARGED，联动床位终末消毒流转（BedService.transferOut 转科
 * 转出床同款）、出院带药放行（DISCHARGE_MED 类 CREATED 医嘱经状态机迁 AUDITED+SYSTEM
 * 审计行+audited.discharge-med 子键事件——M06 撮此摆药）与随访计划生成（出院日后 N 日）。
 * 取消出院=visit 回 ADMITTED 且<b>长期医嘱不复活</b>（停嘱终态保持，恢复治疗须重新开立，
 * Spec 测试边界）。billing 两事件消费回执（结算标记/挂账审批放行）以 IS NULL/BLOCKED
 * 限定 CAS 承载幂等——重复投递零行 info 直返。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class DischargeServiceImpl implements DischargeService {

    /** 无登录上下文场景的操作者回退值（消费线程与审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 出院清理停嘱原因（固定文案，落 stop_reason） */
    private static final String DISCHARGE_STOP_REASON = "出院";

    /** 出院带药放行审核理由（order_audit SYSTEM 行 reason 与状态机迁移留痕共用） */
    private static final String RELEASE_AUDIT_REASON = "出院带药放行";

    /** 审核结论：通过（V905 order_audit.conclusion 词表，与列注释逐字同源） */
    private static final String CONCLUSION_PASSED = "PASSED";

    /** 随访时距缺省（天——出院医嘱三要素之随访必生成，参数缺省 7 日） */
    private static final int DEFAULT_FOLLOW_UP_DAYS = 7;

    /** 随访方式缺省（PHONE 电话） */
    private static final String DEFAULT_FOLLOW_UP_WAY = "PHONE";

    /** 随访内容摘要缺省 */
    private static final String DEFAULT_FOLLOW_UP_SUMMARY = "出院随访";

    /** 随访方式词表（V907 follow_up_plan.way 三值） */
    private static final Set<String> FOLLOW_UP_WAYS = Set.of("PHONE", "WECHAT", "REVISIT");

    /** 医嘱非终态全集（GC19 长期医嘱终态断言与清理追踪清单的判定词表） */
    private static final List<String> NON_TERMINAL_STATUSES = List.of(
            OrderStatus.CREATED.getCode(),
            OrderStatus.AUDIT_REJECTED.getCode(),
            OrderStatus.AUDITED.getCode(),
            OrderStatus.TRANSFERRED.getCode(),
            OrderStatus.EXECUTING.getCode());

    /** 停嘱面可停态（与 MedicalOrderServiceImpl.STOPPABLE_STATUSES 同源词表——本类清理分野口径） */
    private static final List<String> STOPPABLE_STATUSES =
            List.of(OrderStatus.AUDITED.getCode(), OrderStatus.TRANSFERRED.getCode(), OrderStatus.EXECUTING.getCode());

    private final InpatientVisitMapper visitMapper;

    private final MedicalOrderMapper orderMapper;

    private final OrderExecutePlanMapper planMapper;

    private final OrderAuditMapper auditMapper;

    private final DischargeRequestMapper requestMapper;

    private final FollowUpPlanMapper followUpMapper;

    private final InpatientSeqGate seqGate;

    private final MedicalOrderService medicalOrderService;

    private final BedService bedService;

    private final OrderStateMachineService stateMachine;

    private final BillingAccountQueryPort billingAccountQueryPort;

    private final ApplicationEventPublisher events;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 InpatientWebConfig @Import）。
     *
     * @param visitMapper             住院就诊 mapper，非空；状态 CAS 三面（申请/取消/离院确认）
     * @param orderMapper             医嘱主表 mapper，非空；清理分野查询与带药放行值面
     * @param planMapper              执行计划 mapper，非空；在途计划计数与批量作废
     * @param auditMapper             审核流水 mapper，非空；带药放行 SYSTEM 审计行
     * @param requestMapper           出院申请 mapper，非空；申请落库与四 CAS 面
     * @param followUpMapper          随访计划 mapper，非空；离院确认随访生成
     * @param seqGate                 住院业务号发号器（DC 出院申请号），非空
     * @param medicalOrderService     住院医嘱服务（停嘱面复用——出院批量停嘱），非空
     * @param bedService              床位管理服务（终末消毒流转权威），非空
     * @param stateMachine            医嘱状态机服务（带药放行迁移唯一执行面），非空
     * @param billingAccountQueryPort 收费域预审只读端口（billing api），非空；实现归 Task 13
     * @param events                  进程内事件发布器（AFTER_COMMIT 出 MQ），非空
     * @param objectMapper            JSON 序列化器（清理结果快照 JSONB 文本），非空
     */
    public DischargeServiceImpl(
            InpatientVisitMapper visitMapper,
            MedicalOrderMapper orderMapper,
            OrderExecutePlanMapper planMapper,
            OrderAuditMapper auditMapper,
            DischargeRequestMapper requestMapper,
            FollowUpPlanMapper followUpMapper,
            InpatientSeqGate seqGate,
            MedicalOrderService medicalOrderService,
            BedService bedService,
            OrderStateMachineService stateMachine,
            BillingAccountQueryPort billingAccountQueryPort,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper) {
        this.visitMapper = visitMapper;
        this.orderMapper = orderMapper;
        this.planMapper = planMapper;
        this.auditMapper = auditMapper;
        this.requestMapper = requestMapper;
        this.followUpMapper = followUpMapper;
        this.seqGate = seqGate;
        this.medicalOrderService = medicalOrderService;
        this.bedService = bedService;
        this.stateMachine = stateMachine;
        this.billingAccountQueryPort = billingAccountQueryPort;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * 出院申请（「预出院/明日出院」模式）：守卫（在院/离院方式词表）→ 在途清理编排三动作 →
     * 费用预审 → visit CAS + 申请落库（预审态直落）→ 事务内发布 discharge-requested。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空
     * @param req     申请入参，非空
     * @return 申请出参（status=READY/BLOCKED 预审实态），非空
     * @throws BizException IP-1007/IP-1008/IP-1022/IP-1023/IP-1010（接口注全清单）
     */
    @Override
    @Transactional
    public DischargeRequestVO createRequest(String visitId, DischargeRequestCreate req) {
        InpatientVisit visit = requireVisit(visitId);
        // 守卫：出院申请限在院态（已申请/已出院/已作废一律拒）
        if (!VisitStatus.ADMITTED.getCode().equals(visit.getStatus())) {
            throw new BizException(
                    InpatientErrorCode.VISIT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "住院就诊状态不允许申请出院：visitId=" + visitId + "，当前状态=" + visit.getStatus());
        }
        // 离院方式词表裁决（病案首页代码——词表外拒 IP-1022）
        DischargeWay way = DischargeWay.fromCode(req.dischargeWay());
        if (way == null) {
            throw paramInvalid("dischargeWay", req.dischargeWay());
        }
        String requester = OperatorContextHolder.get();
        long requesterId = parseOperatorAsEmployeeId();
        // 在途清理编排三动作（长期批量停嘱/临时追踪清单/未执行计划作废——结果快照入 clearance_result）
        ClearanceSnapshot snapshot = runClearance(visit, requester);
        // 费用预审：billing 权威数据只读快照（结清→READY；欠费→BLOCKED 附欠费额=max(0,未结清-押金余额)）
        DischargePrecheckView precheck = billingAccountQueryPort.precheck(visitId);
        boolean settled = precheck.settled();
        // visit CAS ADMITTED→DISCHARGE_REQUESTED（申请时点库端 now()；0 行=并发重复申请/已迁移）
        if (visitMapper.casRequestDischarge(visitId, requester) == 0) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "出院申请就诊状态并发冲突（已申请/已出院/已作废），本次申请回滚：visitId=" + visitId);
        }
        // 回读申请后行（申请时点由库端 now() 写入禁应用时钟——事件载荷源，admitWard 回读先例）
        InpatientVisit requested = requireVisit(visitId);
        // 申请行落库（预审态直落——清理与预审同事务完成，REQUESTED 为词表保完整的瞬时态）；
        // uk_visit_active 兜底一 visit 至多一条在途申请（并发重复申请 DB 硬防线）
        DischargeRequest row = new DischargeRequest();
        row.setRequestNo(seqGate.nextNo("DC"));
        row.setVisitId(visit.getId());
        row.setPatientId(visit.getPatientId());
        row.setRequesterId(requester);
        row.setRequestedAt(requested.getDischargeRequestedAt());
        row.setExpectDischargeAt(req.expectDischargeAt());
        row.setDischargeWay(way.getCode());
        row.setClearanceResult(serializeClearance(snapshot));
        row.setArrearsAmount(settled ? null : arrearsOf(precheck));
        row.setStatus(settled ? DischargeRequestStatus.READY.getCode() : DischargeRequestStatus.BLOCKED.getCode());
        row.setCreatedBy(requester);
        row.setUpdatedBy(requester);
        try {
            // 数据库写操作：出院申请落库（uk_discharge_request_no/uk_visit_active 双唯一兜底）
            requestMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT, HttpStatus.CONFLICT, "在途出院申请唯一冲突（一就诊至多一条在途申请）：visitId=" + visitId);
        }
        // 事务内发布出院申请事件（AFTER_COMMIT 出 fy.topic；M05 清退在途任务提示、M13 停止计费
        // 与预审依据）；载荷仅定位键与时间线，禁患者姓名/诊断文本
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_VISIT_DISCHARGE_REQUESTED,
                new VisitDischargeRequestedPayload(
                        visitId,
                        visit.getPatientId(),
                        requested.getDischargeRequestedAt().toInstant())));
        log.info(
                "出院申请完成：requestNo={}，visitId={}，patientId={}，预审={}，欠费额(分)={}，停嘱 {} 条/追踪 {} 条/作废计划 {} 条，operator={}",
                row.getRequestNo(),
                visitId,
                visit.getPatientId(),
                row.getStatus(),
                row.getArrearsAmount(),
                snapshot.stoppedLongCount(),
                snapshot.trackedOrders().size(),
                snapshot.cancelledPlanCount(),
                requester);
        return DischargeRequestVO.from(row, visitId);
    }

    /**
     * 取消出院申请：仅 REQUESTED 态可取消；visit 回 ADMITTED——<b>长期医嘱不复活</b>（停嘱为
     * 终态迁移，恢复治疗须重新开立；追踪清单停留医嘱状态面零变更——清理只记追踪不迁移）。
     *
     * @param requestNo 出院申请单号，非空
     * @return 取消后出参（status=CANCELLED），非空
     * @throws BizException IP-1018/IP-1017/IP-1022/IP-1023
     */
    @Override
    @Transactional
    public DischargeRequestVO cancel(String requestNo) {
        DischargeRequest row = requireRequest(requestNo);
        String operator = OperatorContextHolder.get();
        // 操作者标识守卫（缺失/非数字拒 IP-1022——取消为审计动作，主体必可定位）
        parseOperatorAsEmployeeId();
        // 状态守卫：仅申请中可取消（READY/BLOCKED 须先经业务裁决——挂账审批/重新预审路径）
        if (!DischargeRequestStatus.REQUESTED.getCode().equals(row.getStatus())) {
            throw new BizException(
                    InpatientErrorCode.DISCHARGE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "仅申请中（REQUESTED）出院申请可取消：requestNo=" + requestNo + "，当前状态=" + row.getStatus());
        }
        InpatientVisit visit = visitMapper.selectById(row.getVisitId());
        if (visit == null) {
            throw new BizException(
                    InpatientErrorCode.VISIT_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "出院申请关联住院就诊不存在（数据不一致）：requestNo=" + requestNo);
        }
        // visit CAS DISCHARGE_REQUESTED→ADMITTED（0 行=并发离院确认——取消与确认互斥窗口）
        if (visitMapper.casCancelDischarge(visit.getVisitId(), operator) == 0) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "取消出院就诊状态并发冲突（离院确认/作废先行），本次取消回滚：visitId=" + visit.getVisitId());
        }
        // 申请 CAS REQUESTED→CANCELLED（0 行=并发迁移兜底）；医嘱不复活——停嘱终态保持零恢复动作
        if (requestMapper.casCancel(requestNo, operator) == 0) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT, HttpStatus.CONFLICT, "取消出院申请并发冲突（状态已迁移）：requestNo=" + requestNo);
        }
        row.setStatus(DischargeRequestStatus.CANCELLED.getCode());
        log.info(
                "取消出院完成（长期医嘱不复活，恢复治疗须重新开立）：requestNo={}，visitId={}，operator={}",
                requestNo,
                visit.getVisitId(),
                operator);
        return DischargeRequestVO.from(row, visit.getVisitId());
    }

    /**
     * 在途清理与预审结果查询：清理快照（clearance_result JSONB 解析）+ 预审状态与欠费额 +
     * 结算标记与挂账审批凭证——人工处置取数面。
     *
     * @param requestNo 出院申请单号，非空
     * @return 清理与预审结果出参，非空
     * @throws BizException IP-1018/IP-1007
     */
    @Override
    @Transactional(readOnly = true)
    public ClearanceVO clearance(String requestNo) {
        DischargeRequest row = requireRequest(requestNo);
        InpatientVisit visit = visitMapper.selectById(row.getVisitId());
        if (visit == null) {
            throw new BizException(
                    InpatientErrorCode.VISIT_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "出院申请关联住院就诊不存在（数据不一致）：requestNo=" + requestNo);
        }
        ClearanceSnapshot snapshot = deserializeClearance(row.getClearanceResult(), requestNo);
        return new ClearanceVO(
                requestNo,
                visit.getVisitId(),
                row.getStatus(),
                snapshot.stoppedLongCount(),
                snapshot.trackedOrders(),
                snapshot.cancelledPlanCount(),
                row.getArrearsAmount(),
                row.getSettlementCompletedAt(),
                row.getApprovalNo());
    }

    /**
     * 离院确认（GC19 离院前置校验红线承载）：三重前置校验 → 带药放行 → visit/申请双 CAS →
     * 床位终末消毒 → 随访生成 → discharged 事件；任一步失败异常传播整体回滚。
     *
     * @param requestNo 出院申请单号，非空
     * @param req       确认入参（随访三参数可选），非空
     * @return 确认后出参（status=COMPLETED），非空
     * @throws BizException IP-1018/IP-1017/IP-1022/IP-1023/IP-1010
     */
    @Override
    @Transactional
    public DischargeRequestVO confirm(String requestNo, DischargeConfirmRequest req) {
        DischargeRequest row = requireRequest(requestNo);
        String operator = OperatorContextHolder.get();
        long operatorId = parseOperatorAsEmployeeId();
        // GC19 前置①：预审 READY（BLOCKED=欠费未放行；COMPLETED/CANCELLED=已处置——均拒）
        if (!DischargeRequestStatus.READY.getCode().equals(row.getStatus())) {
            throw new BizException(
                    InpatientErrorCode.DISCHARGE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "离院确认放行条件未满足：申请状态非 READY（当前=" + row.getStatus() + "）——欠费须挂账审批放行或结清后重新预审：requestNo=" + requestNo);
        }
        // GC19 前置②：出院结算完成标记（billing.settlement.completed 消费落值——双条件之一）
        if (row.getSettlementCompletedAt() == null) {
            throw new BizException(
                    InpatientErrorCode.DISCHARGE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "离院确认放行条件未满足：出院结算未完成（结算完成后方可确认离院）：requestNo=" + requestNo);
        }
        // GC19 前置③：全部长期医嘱已终态（追踪清单内停留医嘱须先人工处置——作废/驳回重提）
        rejectIfOngoingLongOrders(row.getVisitId(), requestNo);
        // GC19 前置④：在途执行计划清零（申请时点已全量作废——此处防御后续新增/漏网）
        rejectIfPendingPlans(row.getVisitId(), requestNo);
        InpatientVisit visit = visitMapper.selectById(row.getVisitId());
        if (visit == null) {
            throw new BizException(
                    InpatientErrorCode.VISIT_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "出院申请关联住院就诊不存在（数据不一致）：requestNo=" + requestNo);
        }
        // 出院带药放行：DISCHARGE_MED 类 CREATED 医嘱迁 AUDITED + audited.discharge-med 子键事件
        releaseDischargeMeds(visit, operator, operatorId);
        // visit CAS DISCHARGE_REQUESTED→DISCHARGED（出院时点库端 now()，离院方式誊写病案统计口径）
        if (visitMapper.casDischarge(visit.getVisitId(), row.getDischargeWay(), operator) == 0) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "离院确认就诊状态并发冲突（取消/确认先行），本次确认回滚：visitId=" + visit.getVisitId());
        }
        // 申请 CAS READY→COMPLETED 终态（0 行=并发迁移兜底）
        if (requestMapper.casComplete(requestNo, operator) == 0) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT, HttpStatus.CONFLICT, "离院确认申请状态并发冲突（状态已迁移）：requestNo=" + requestNo);
        }
        // 床位 OCCUPIED→DISINFECTING 终末消毒流转（bed_assign 闭合 + bed.changed——转科转出床同款；
        // 在院必有床位，缺失=数据不一致 fail-closed）
        if (visit.getCurrentBedId() == null) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "在院就诊无当前床位（数据不一致），禁止离院确认：visitId=" + visit.getVisitId());
        }
        bedService.transferOut(visit.getCurrentBedId(), visit.getVisitId());
        // 回读出院后行（出院时点由库端 now() 写入——随访日期与事件载荷源）
        InpatientVisit discharged = visitMapper.selectById(visit.getId());
        if (discharged == null || discharged.getDischargedAt() == null) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "离院确认后就诊行回读缺失（并发逻辑删）：visitId=" + visit.getVisitId());
        }
        // 随访计划生成（出院医嘱三要素之随访——出院必随随访，plan_date=出院日后 N 日）
        insertFollowUpPlan(row, discharged, req, operator);
        // 事务内发布出院终态事件（M05 终清在途任务、M14 强制解绑、M19 统计入池依据）
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_VISIT_DISCHARGED,
                new VisitDischargedPayload(
                        visit.getVisitId(),
                        visit.getPatientId(),
                        discharged.getDischargedAt().toInstant())));
        row.setStatus(DischargeRequestStatus.COMPLETED.getCode());
        log.info(
                "离院确认完成：requestNo={}，visitId={}，patientId={}，离院方式={}，随访 {} 日后，operator={}",
                requestNo,
                visit.getVisitId(),
                visit.getPatientId(),
                row.getDischargeWay(),
                followUpDays(req),
                operator);
        return DischargeRequestVO.from(row, visit.getVisitId());
    }

    /**
     * 结算完成回执消费体（billing.settlement.completed，结算类型=IN 出院结算分支）：
     * visitId 反查在途申请落结算标记——IS NULL 限定 CAS 幂等，重复投递/无在途申请零行直返。
     *
     * @param visitId   CF-3 住院就诊号（载荷原值），非空
     * @param settledAt 结算完成时点（信封 occurredAt），非空
     */
    @Override
    @Transactional
    public void onSettlementCompleted(String visitId, Instant settledAt) {
        DischargeRequest active = findActiveByVisitNo(visitId);
        if (active == null) {
            // 在途中结算（未申请出院）或已终态申请——非本消费面处置场景，info 留痕直返
            log.info("结算完成回执无在途出院申请（跳过标记）：visitId={}，settledAt={}", visitId, settledAt);
            return;
        }
        OffsetDateTime at = OffsetDateTime.ofInstant(settledAt, ZoneOffset.UTC);
        if (requestMapper.casMarkSettled(active.getRequestNo(), at, consumerOperator()) == 0) {
            log.info("结算完成标记幂等跳过（已标记/申请已终态）：requestNo={}", active.getRequestNo());
            return;
        }
        log.info(
                "出院结算完成标记落值：requestNo={}，visitId={}，settledAt={}，operator={}",
                active.getRequestNo(),
                visitId,
                settledAt,
                consumerOperator());
    }

    /**
     * 挂账审批放行回执消费体（billing.arrears.approved）：BLOCKED 态在途申请 CAS 转 READY
     * 并记录审批单号（放行凭证留痕）；非 BLOCKED 态零行幂等直返。
     *
     * @param visitId    CF-3 住院就诊号（载荷原值），非空
     * @param approvalNo 挂账审批单号，非空
     */
    @Override
    @Transactional
    public void onArrearsApproved(String visitId, String approvalNo) {
        DischargeRequest active = findActiveByVisitNo(visitId);
        if (active == null) {
            log.info("挂账审批回执无在途出院申请（跳过放行）：visitId={}，approvalNo={}", visitId, approvalNo);
            return;
        }
        if (requestMapper.casApproveArrears(active.getRequestNo(), approvalNo, consumerOperator()) == 0) {
            log.info(
                    "挂账审批放行幂等跳过（非 BLOCKED 态——已 READY/已终态）：requestNo={}，approvalNo={}",
                    active.getRequestNo(),
                    approvalNo);
            return;
        }
        log.info(
                "挂账审批放行完成（BLOCKED→READY）：requestNo={}，visitId={}，approvalNo={}，operator={}",
                active.getRequestNo(),
                visitId,
                approvalNo,
                consumerOperator());
    }

    /**
     * 在途清理编排三动作（申请事务内，Spec FU-M04-07 时序冻结）：①长期可停医嘱批量停嘱
     * （复用 stopAllForTransfer 停嘱面——状态机唯一裁决，停嘱联动未来计划作废与 stopped
     * 事件随停嘱面承载）；②追踪清单=非终态医嘱中停嘱面未覆盖行（CREATED/AUDIT_REJECTED
     * 停留医嘱无合法停嘱边[04 Spec §3.3 冻结]与临时在途医嘱——人工处置，不迁移状态）；
     * ③未执行计划全量作废（计数=清理时点在途 PENDING 总数——stopInternal 未来时点作废外的
     * 补齐全量：当日已过时点 PENDING/临时医嘱 PENDING 一并冻结）。
     *
     * @param visit    申请就诊行（ADMITTED），非空
     * @param operator 操作者（计划作废审计留痕），非空
     * @return 清理结果快照（入 clearance_result JSONB），非空
     */
    private ClearanceSnapshot runClearance(InpatientVisit visit, String operator) {
        // 就诊医嘱全集单查询（清理分野的数据源——避免分面多次查询）
        List<MedicalOrder> allOrders = orderMapper.selectList(
                Wrappers.<MedicalOrder>lambdaQuery().eq(MedicalOrder::getVisitId, visit.getId()));
        // 清理时点在途 PENDING 计数（GC19 在途计划清零的申请时点基准——全部在本编排内作废）
        Long pendingBefore = planMapper.selectCount(Wrappers.<OrderExecutePlan>lambdaQuery()
                .eq(OrderExecutePlan::getVisitId, visit.getId())
                .eq(OrderExecutePlan::getStatus, PlanStatus.PENDING.getCode()));
        List<MedicalOrder> ongoing = allOrders.stream()
                .filter(order -> NON_TERMINAL_STATUSES.contains(order.getStatus()))
                .toList();
        // 动作①：长期可停医嘱批量停嘱（LONG∩可停态；空集直过——停嘱面内部空集语义同款）
        long stoppedLongCount = ongoing.stream()
                .filter(order -> OrderClass.LONG.getCode().equals(order.getOrderClass())
                        && STOPPABLE_STATUSES.contains(order.getStatus()))
                .count();
        if (stoppedLongCount > 0) {
            medicalOrderService.stopAllForTransfer(visit.getId(), DISCHARGE_STOP_REASON);
        }
        // 动作②：追踪清单=非终态医嘱减停嘱面覆盖行（人工处置面——作废/驳回重提/等待执行完成）
        List<ClearanceVO.TrackedOrderVO> trackedOrders = ongoing.stream()
                .filter(order -> !(OrderClass.LONG.getCode().equals(order.getOrderClass())
                        && STOPPABLE_STATUSES.contains(order.getStatus())))
                .map(order ->
                        new ClearanceVO.TrackedOrderVO(order.getOrderNo(), order.getOrderClass(), order.getStatus()))
                .toList();
        // 动作③：未执行计划全量作废（复用 cancelPendingByOrderIds 条件更新面——就诊全部医嘱，
        // 兜底停嘱面未来时点作废外的遗漏；空医嘱集或零在途计划直过）
        if (!allOrders.isEmpty() && pendingBefore != null && pendingBefore > 0) {
            int cancelled = planMapper.cancelPendingByOrderIds(
                    allOrders.stream().map(MedicalOrder::getId).toList(), operator);
            log.info(
                    "出院清理在途计划全量作废：visitId(pk)={}，作废 {} 条（清理时点在途 {} 条），operator={}",
                    visit.getId(),
                    cancelled,
                    pendingBefore,
                    operator);
        }
        return new ClearanceSnapshot(
                (int) stoppedLongCount, trackedOrders, pendingBefore == null ? 0 : pendingBefore.intValue());
    }

    /**
     * 出院带药放行（离院确认时点，Spec FU-M04-07）：DISCHARGE_MED 类 CREATED 停留医嘱
     * （审核链停留待放行态——order.created 子键为 discharge-med，M06 不据此建审方任务）经
     * 状态机迁 AUDITED（留痕 reason=出院带药放行）+ 生效时点落值 + SYSTEM 审计行 +
     * inpatient.order.audited.discharge-med 子键事件（M06 撮此摆药、M13 撮此计价出院带药）。
     * 空集直过（无带药医嘱为常态场景）。
     *
     * @param visit     离院就诊行，非空
     * @param operator  操作者 string（审计留痕），非空
     * @param operatorId 操作者员工 ID（状态机迁移留痕），非空
     */
    private void releaseDischargeMeds(InpatientVisit visit, String operator, long operatorId) {
        List<MedicalOrder> meds = orderMapper.selectList(Wrappers.<MedicalOrder>lambdaQuery()
                .eq(MedicalOrder::getVisitId, visit.getId())
                .eq(MedicalOrder::getOrderType, OrderType.DISCHARGE_MED.getCode())
                .eq(MedicalOrder::getStatus, OrderStatus.CREATED.getCode()));
        for (MedicalOrder order : meds) {
            OffsetDateTime releasedAt = OffsetDateTime.now();
            // 状态机迁移 CREATED→AUDITED（合法边；唯一裁决面——留痕随状态机自动落 order_status_log）
            stateMachine.transition(order, OrderStatus.AUDITED, RELEASE_AUDIT_REASON, operatorId);
            // 生效时点落值（与审核链过审副作用同源——0 行=并发逻辑删窗口定性冲突）
            if (orderMapper.updateAuditBegin(order.getOrderNo(), releasedAt, operator) == 0) {
                throw new BizException(
                        InpatientErrorCode.CONFLICT,
                        HttpStatus.CONFLICT,
                        "出院带药放行生效时点落写零行（并发逻辑删窗口）：orderNo=" + order.getOrderNo());
            }
            insertReleaseAuditRow(order, operator, releasedAt);
            // audited 子键事件（routing key=inpatient.order.audited.discharge-med——M06 摆药/计价依据）
            events.publishEvent(new InpatientDomainEvent(
                    InpatientMessagingConstants.withTypeKey(
                            InpatientMessagingConstants.EVENT_ORDER_AUDITED, OrderType.DISCHARGE_MED.subKey()),
                    new OrderAuditedPayload(
                            order.getOrderNo(),
                            visit.getVisitId(),
                            order.getPatientId(),
                            AuditStage.SYSTEM.getCode(),
                            operator,
                            releasedAt.toInstant())));
        }
        if (!meds.isEmpty()) {
            log.info(
                    "出院带药放行完成：visitId={}，放行 {} 条（audited.discharge-med 子键事件），operator={}",
                    visit.getVisitId(),
                    meds.size(),
                    operator);
        }
    }

    /**
     * 随访计划生成（离院确认同事务——出院必随随访）：plan_date=出院日后 N 日（请求参数缺省
     * 7 日），方式/摘要缺省电话/「出院随访」；方式词表外拒 IP-1022（模块内直调场景防御，
     * Web 层 @Pattern 兜底）。
     *
     * @param row        出院申请行，非空
     * @param discharged 出院后就诊行（dischargedAt 库端回读），非空
     * @param req        确认入参（随访三参数），非空
     * @param operator   操作者（审计留痕），非空
     */
    private void insertFollowUpPlan(
            DischargeRequest row, InpatientVisit discharged, DischargeConfirmRequest req, String operator) {
        String way = isBlank(req.followUpWay()) ? DEFAULT_FOLLOW_UP_WAY : req.followUpWay();
        if (!FOLLOW_UP_WAYS.contains(way)) {
            throw paramInvalid("followUpWay", req.followUpWay());
        }
        FollowUpPlan plan = new FollowUpPlan();
        plan.setVisitId(row.getVisitId());
        plan.setPatientId(row.getPatientId());
        // 随访日期=出院日（库端出院时点的本地日期）+ N 日
        plan.setPlanDate(discharged.getDischargedAt().toLocalDate().plusDays(followUpDays(req)));
        plan.setWay(way);
        plan.setSummary(isBlank(req.followUpSummary()) ? DEFAULT_FOLLOW_UP_SUMMARY : req.followUpSummary());
        plan.setStatus(FollowUpStatus.PENDING.getCode());
        plan.setCreatedBy(operator);
        plan.setUpdatedBy(operator);
        // 数据库写操作：随访计划落库（与离院确认同事务成败与共）
        followUpMapper.insert(plan);
    }

    /**
     * GC19 前置③实现：非终态长期医嘱存在即拒 IP-1017（申请时点停嘱面已处理可停行——此处
     * 残留即追踪清单内停留医嘱，须人工处置[作废/驳回重提]后方可离院）。
     *
     * @param visitPk   住院就诊主键，非空
     * @param requestNo 出院申请单号（拒绝文案定位），非空
     */
    private void rejectIfOngoingLongOrders(Long visitPk, String requestNo) {
        Long count = orderMapper.selectCount(Wrappers.<MedicalOrder>lambdaQuery()
                .eq(MedicalOrder::getVisitId, visitPk)
                .eq(MedicalOrder::getOrderClass, OrderClass.LONG.getCode())
                .in(MedicalOrder::getStatus, NON_TERMINAL_STATUSES));
        if (count != null && count > 0) {
            throw new BizException(
                    InpatientErrorCode.DISCHARGE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "离院确认放行条件未满足：存在非终态长期医嘱 " + count + " 条（追踪清单停留医嘱须先人工处置）：requestNo=" + requestNo);
        }
    }

    /**
     * GC19 前置④实现：在途 PENDING 计划存在即拒 IP-1017（申请时点已全量作废——此处残留为
     * 后续新增/漏网防御，属数据不一致面）。
     *
     * @param visitPk   住院就诊主键，非空
     * @param requestNo 出院申请单号（拒绝文案定位），非空
     */
    private void rejectIfPendingPlans(Long visitPk, String requestNo) {
        Long count = planMapper.selectCount(Wrappers.<OrderExecutePlan>lambdaQuery()
                .eq(OrderExecutePlan::getVisitId, visitPk)
                .eq(OrderExecutePlan::getStatus, PlanStatus.PENDING.getCode()));
        if (count != null && count > 0) {
            throw new BizException(
                    InpatientErrorCode.DISCHARGE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "离院确认放行条件未满足：在途执行计划未清零（" + count + " 条 PENDING）：requestNo=" + requestNo);
        }
    }

    /**
     * 带药放行 SYSTEM 审计行落库（只增——与审核链过审行同构，reason=出院带药放行）。
     *
     * @param order      放行医嘱行（状态已迁 AUDITED），非空
     * @param operator   操作者 string，非空
     * @param releasedAt 放行时点，非空
     */
    private void insertReleaseAuditRow(MedicalOrder order, String operator, OffsetDateTime releasedAt) {
        OrderAudit auditRow = new OrderAudit();
        auditRow.setOrderId(order.getId());
        auditRow.setStage(AuditStage.SYSTEM.getCode());
        auditRow.setReviewTaskNo(null);
        auditRow.setConclusion(CONCLUSION_PASSED);
        auditRow.setReason(RELEASE_AUDIT_REASON);
        auditRow.setAuditOperator(operator);
        auditRow.setOccurredAt(releasedAt);
        auditRow.setCreatedBy(operator);
        auditRow.setUpdatedBy(operator);
        // 数据库写操作：审核流水只增落库（V905 order_audit）
        auditMapper.insert(auditRow);
    }

    /**
     * 按就诊号定位在途出院申请（billing 回执消费面：visitId 号→行主键→在途申请）。
     *
     * @param visitNo CF-3 住院就诊号（I 型 14 位，载荷原值），非空
     * @return 在途申请行（REQUESTED/READY/BLOCKED）；无在途申请返回 null
     * @throws IllegalStateException 载荷 visitId 无法定位住院就诊（数据不一致——死信留痕口径
     *                 与 PharmacyAuditReplyListener 缺 target 同款 fail-closed）
     */
    private DischargeRequest findActiveByVisitNo(String visitNo) {
        InpatientVisit visit =
                visitMapper.selectOne(Wrappers.<InpatientVisit>lambdaQuery().eq(InpatientVisit::getVisitId, visitNo));
        if (visit == null) {
            throw new IllegalStateException("billing 回执载荷 visitId 无法定位住院就诊（数据不一致）：" + visitNo);
        }
        return requestMapper.selectOne(Wrappers.<DischargeRequest>lambdaQuery()
                .eq(DischargeRequest::getVisitId, visit.getId())
                .in(
                        DischargeRequest::getStatus,
                        DischargeRequestStatus.REQUESTED.getCode(),
                        DischargeRequestStatus.READY.getCode(),
                        DischargeRequestStatus.BLOCKED.getCode()));
    }

    /** 欠费额计算（快照回显 GC18）：max(0, 未结清合计-押金余额)——押金覆盖内不产生欠费。 */
    private static Long arrearsOf(DischargePrecheckView precheck) {
        long unsettled = precheck.unsettledAmount() == null ? 0L : precheck.unsettledAmount();
        long deposit = precheck.depositBalance() == null ? 0L : precheck.depositBalance();
        return Math.max(0L, unsettled - deposit);
    }

    /** 随访时距取值（缺省 7 日）。 */
    private static int followUpDays(DischargeConfirmRequest req) {
        return req.followUpDays() == null ? DEFAULT_FOLLOW_UP_DAYS : req.followUpDays();
    }

    /** 清理结果快照序列化（clearance_result JSONB 文本）。 */
    private String serializeClearance(ClearanceSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            // record 纯值序列化无递归/无自定义器——不可达防御，fail-closed 阻断申请落库
            throw new BizException(InpatientErrorCode.CONFLICT, HttpStatus.CONFLICT, "清理结果快照序列化失败（不可达防御）");
        }
    }

    /**
     * 清理结果快照反序列化（clearance 查询面；空值防御回空快照，损坏 JSON 定性数据不一致）。
     *
     * @param json      快照 JSON 文本，可空
     * @param requestNo 申请单号（异常文案定位），非空
     */
    private ClearanceSnapshot deserializeClearance(String json, String requestNo) {
        if (json == null || json.isBlank()) {
            return new ClearanceSnapshot(0, List.of(), 0);
        }
        try {
            return objectMapper.readValue(json, ClearanceSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT, HttpStatus.CONFLICT, "清理结果快照解析失败（数据不一致）：requestNo=" + requestNo);
        }
    }

    /** 按就诊号定位行（未命中定性 IP-1007；逻辑删由 @TableLogic 自动过滤）。 */
    private InpatientVisit requireVisit(String visitId) {
        InpatientVisit visit =
                visitMapper.selectOne(Wrappers.<InpatientVisit>lambdaQuery().eq(InpatientVisit::getVisitId, visitId));
        if (visit == null) {
            throw new BizException(InpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "住院就诊不存在：" + visitId);
        }
        return visit;
    }

    /** 按申请单号定位行（未命中定性 IP-1018；逻辑删由 @TableLogic 自动过滤）。 */
    private DischargeRequest requireRequest(String requestNo) {
        DischargeRequest row = requestMapper.selectOne(
                Wrappers.<DischargeRequest>lambdaQuery().eq(DischargeRequest::getRequestNo, requestNo));
        if (row == null) {
            throw new BizException(
                    InpatientErrorCode.DISCHARGE_REQUEST_NOT_FOUND, HttpStatus.NOT_FOUND, "出院申请不存在：" + requestNo);
        }
        return row;
    }

    /**
     * 操作者标识解析为员工 ID（REST 面口径；缺失/非数字显式 IP-1022 拒绝——W-22⑦ 同款守卫）。
     *
     * @return 员工 ID，非空
     * @throws BizException IP-1022 操作者标识缺失或非数字时触发
     */
    private static long parseOperatorAsEmployeeId() {
        String operator = OperatorContextHolder.get();
        if (operator == null || !operator.matches("\\d+")) {
            throw new BizException(
                    InpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "操作者标识缺失或非数字（无法定位出院操作主体）：" + maskOperator(operator));
        }
        return Long.parseLong(operator);
    }

    /** 消费线程操作者取值（无登录上下文回退 system，与审计列默认同源）。 */
    private static String consumerOperator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }

    /** 工号脱敏（等保三级口径，禁明文工号出 ProblemDetail/日志）：首尾各留 1 位，中段 ***。 */
    private static String maskOperator(String operator) {
        if (operator == null || operator.length() <= 2) {
            return "***";
        }
        return operator.charAt(0) + "***" + operator.charAt(operator.length() - 1);
    }

    /** 入参格式非法（IP-1022）统一构造：词表外/结构校验不过。 */
    private static BizException paramInvalid(String field, String value) {
        return new BizException(
                InpatientErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "入参格式非法——" + field + " 词表外：" + value);
    }

    /** 空串判定（null 或全空白）。 */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 在途清理结果快照（clearance_result JSONB 载体——序列化字段名与 V907 列注释声明同源：
     * stoppedLongCount/trackedOrders[]/cancelledPlanCount）。包级可见（Jackson 反序列化
     * clearance 查询面复用——同包测试直构）。
     *
     * @param stoppedLongCount  长期医嘱批量停嘱数（清理动作①）
     * @param trackedOrders     追踪清单（清理动作②——人工处置面）
     * @param cancelledPlanCount 未执行计划作废数（清理动作③——清理时点在途 PENDING 总数）
     */
    record ClearanceSnapshot(
            int stoppedLongCount, List<ClearanceVO.TrackedOrderVO> trackedOrders, int cancelledPlanCount) {}
}
