package com.fuyun.inpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.OrderAuditRejectedPayload;
import com.fuyun.inpatient.api.payload.OrderAuditedPayload;
import com.fuyun.inpatient.api.payload.OrderCancelledPayload;
import com.fuyun.inpatient.api.payload.OrderRevokedPayload;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.OrderReorganizeRequest;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.entity.MedicalOrder;
import com.fuyun.inpatient.entity.MedicalOrderItem;
import com.fuyun.inpatient.entity.OrderAudit;
import com.fuyun.inpatient.entity.OrderStatusLog;
import com.fuyun.inpatient.enums.AuditStage;
import com.fuyun.inpatient.enums.OrderStatus;
import com.fuyun.inpatient.enums.OrderType;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.mapper.MedicalOrderItemMapper;
import com.fuyun.inpatient.mapper.MedicalOrderMapper;
import com.fuyun.inpatient.mapper.OrderAuditMapper;
import com.fuyun.inpatient.mapper.OrderStatusLogMapper;
import com.fuyun.inpatient.service.OrderAuditService;
import com.fuyun.inpatient.service.OrderStateMachineService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 医嘱审核与控制服务实现（FU-M04-05，V905 两表业务面）。审核链两阶段：SYSTEM 系统自动审核
 * （开立后全员必经——非用药类过审迁移 AUDITED + audited 子键事件；用药类停留 CREATED 落
 * SYSTEM 预进行待 M06 药师审）与 PHARMACIST 药师审方回执（PharmacyAuditReplyListener 消费
 * V800 id 53/54 驱动迁移与 audit-rejected 发布，重复回执按已达态幂等跳过）。控制面：作废
 * （仅未产生执行，cancelled 事件驱动 M05 撤执行单）/撤回重审（仅转抄前，revoked 事件）/
 * 重整（只留痕不迁移状态）/口头医嘱补录确认（oral_confirmed_at 落值）。一切状态迁移唯一经
 * OrderStateMachineService（GC17——迁移留痕由状态机回接后的 order_status_log 自动落库，
 * 本类仅重整留痕直写日志表——from=to 无迁移动作）。事件载荷禁患者姓名/诊断文本（GC22）。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口，回执消费路径事务由监听器线程
 * 经本类事务边界承载（AFTER_COMMIT 出 MQ 不落在事务内）。
 */
@Slf4j
public class OrderAuditServiceImpl implements OrderAuditService {

    /** 审核结论：通过（V905 order_audit.conclusion 词表，与列注释逐字同源） */
    private static final String CONCLUSION_PASSED = "PASSED";

    /** 审核结论：驳回（V905 order_audit.conclusion 词表，与列注释逐字同源） */
    private static final String CONCLUSION_REJECTED = "REJECTED";

    /** 重整留痕固定原因（04 Spec §4：重整只重排视图并留痕） */
    private static final String REASON_REORGANIZE = "医嘱重整";

    private final MedicalOrderMapper orderMapper;

    private final MedicalOrderItemMapper itemMapper;

    private final OrderAuditMapper auditMapper;

    private final OrderStatusLogMapper statusLogMapper;

    private final InpatientVisitMapper visitMapper;

    private final OrderStateMachineService stateMachine;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 InpatientWebConfig @Import）。
     *
     * @param orderMapper     医嘱主表 mapper，非空；审核生效时点/撤回复位/补录确认值面更新
     * @param itemMapper      医嘱明细 mapper，非空；口头医嘱行存在性校验（oral_flag）
     * @param auditMapper     审核流水 mapper，非空；SYSTEM/PHARMACIST 两阶段结论落行
     * @param statusLogMapper 状态迁移日志 mapper，非空；重整留痕（from=to）直写面
     * @param visitMapper     住院就诊 mapper，非空；事件载荷 visitId 号映射
     * @param stateMachine    医嘱状态机服务（状态迁移唯一执行面），非空
     * @param events          进程内事件发布器（AFTER_COMMIT 出 MQ），非空
     */
    public OrderAuditServiceImpl(
            MedicalOrderMapper orderMapper,
            MedicalOrderItemMapper itemMapper,
            OrderAuditMapper auditMapper,
            OrderStatusLogMapper statusLogMapper,
            InpatientVisitMapper visitMapper,
            OrderStateMachineService stateMachine,
            ApplicationEventPublisher events) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.auditMapper = auditMapper;
        this.statusLogMapper = statusLogMapper;
        this.visitMapper = visitMapper;
        this.stateMachine = stateMachine;
        this.events = events;
    }

    /**
     * 系统自动审核（开立/驳回重提链全员必经）：用药类停留 CREATED 待药师审（落 SYSTEM 预进行）；
     * 非用药类过审迁移 AUDITED + 生效时点落值 + audited 子键事件。
     */
    @Override
    @Transactional
    public OrderStatus audit(String orderNo) {
        MedicalOrder order = requireOrder(orderNo);
        // 医嘱类型词表外=主数据脏数据（fail-closed：审核链无法裁决用药面）
        OrderType orderType = requireOrderType(order);
        long operator = parseOperatorAsEmployeeId();
        if (orderType.isMedication()) {
            // 用药类：系统预检通过（开立四层校验已过）停留 CREATED——语义=待药师审（04 Spec 红线 2，
            // 以 order_audit.stage 区分不新增状态）；只落 SYSTEM 预进行，不迁移不发事件
            insertAuditRow(
                    order,
                    AuditStage.SYSTEM,
                    CONCLUSION_PASSED,
                    null,
                    "系统预检通过，待药师审方",
                    String.valueOf(operator),
                    OffsetDateTime.now());
            log.info(
                    "用药类医嘱系统预检通过（停留 CREATED 待药师审）：orderNo={}，orderType={}，operator={}",
                    orderNo,
                    orderType.getCode(),
                    operator);
            return OrderStatus.CREATED;
        }
        // 非用药类：系统自动过审——状态机唯一迁移面（留痕随状态机自动落 order_status_log）
        OffsetDateTime auditedAt = OffsetDateTime.now();
        stateMachine.transition(order, OrderStatus.AUDITED, "系统自动审核通过", operator);
        // 过审副作用收口（生效时点 + SYSTEM 审计行 + audited 子键事件）与药师通过回执同链复用
        applyAuditedSideEffects(order, orderType, AuditStage.SYSTEM, null, "系统自动审核通过", operator, auditedAt);
        log.info(
                "非用药类医嘱系统自动过审：orderNo={}，orderType={}，routingKey={}，operator={}",
                orderNo,
                orderType.getCode(),
                InpatientMessagingConstants.withTypeKey(
                        InpatientMessagingConstants.EVENT_ORDER_AUDITED, orderType.subKey()),
                operator);
        return OrderStatus.AUDITED;
    }

    /** 药师审方通过回执：CREATED→AUDITED + PHARMACIST 审计行 + audited.子键事件；重复回执幂等跳过。 */
    @Override
    @Transactional
    public void onPharmacistApproved(String orderNo, String reviewTaskNo, String auditOperator, Instant auditedAt) {
        MedicalOrder order = requireOrder(orderNo);
        // 重复回执幂等：医嘱已 AUDITED（首次回执已迁移）零副作用直返——仅首次生效
        if (OrderStatus.AUDITED.getCode().equals(order.getStatus())) {
            log.info("重复审方通过回执幂等跳过（医嘱已 AUDITED）：orderNo={}，reviewTaskNo={}", orderNo, reviewTaskNo);
            return;
        }
        OrderType orderType = requireOrderType(order);
        long operator = parseReplyOperator(auditOperator);
        OffsetDateTime at = toOffsetDateTime(auditedAt);
        // 状态机迁移（非 CREATED 态拒 IP-1010 传播进死信留痕——fail-closed；留痕随状态机落库）
        stateMachine.transition(order, OrderStatus.AUDITED, "药师审方通过", operator);
        applyAuditedSideEffects(order, orderType, AuditStage.PHARMACIST, reviewTaskNo, "药师审方通过", operator, at);
        log.info(
                "药师审方通过回执消费完成：orderNo={}，reviewTaskNo={}，routingKey={}，operator={}",
                orderNo,
                reviewTaskNo,
                InpatientMessagingConstants.withTypeKey(
                        InpatientMessagingConstants.EVENT_ORDER_AUDITED, orderType.subKey()),
                operator);
    }

    /** 药师审方驳回回执：CREATED→AUDIT_REJECTED + PHARMACIST 驳回审计行 + audit-rejected 事件；重复回执幂等跳过。 */
    @Override
    @Transactional
    public void onPharmacistRejected(
            String orderNo, String reviewTaskNo, String rejectReason, String auditOperator, Instant auditedAt) {
        MedicalOrder order = requireOrder(orderNo);
        // 重复回执幂等：医嘱已 AUDIT_REJECTED（首次回执已迁移）零副作用直返
        if (OrderStatus.AUDIT_REJECTED.getCode().equals(order.getStatus())) {
            log.info("重复审方驳回回执幂等跳过（医嘱已 AUDIT_REJECTED）：orderNo={}，reviewTaskNo={}", orderNo, reviewTaskNo);
            return;
        }
        long operator = parseReplyOperator(auditOperator);
        OffsetDateTime at = toOffsetDateTime(auditedAt);
        // 驳回原因即迁移留痕原因（医生站重提路径的修改依据）
        stateMachine.transition(order, OrderStatus.AUDIT_REJECTED, rejectReason, operator);
        insertAuditRow(
                order,
                AuditStage.PHARMACIST,
                CONCLUSION_REJECTED,
                reviewTaskNo,
                rejectReason,
                String.valueOf(operator),
                at);
        // 驳回事件（V901 id 67 载荷，登记名不带子键）：医生站据此走修改重提路径
        String visitNo = visitNoOf(order.getVisitId());
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_ORDER_AUDIT_REJECTED,
                new OrderAuditRejectedPayload(orderNo, visitNo, order.getPatientId(), rejectReason, auditedAt)));
        log.info(
                "药师审方驳回回执消费完成：orderNo={}，reviewTaskNo={}，visitNo={}，operator={}",
                orderNo,
                reviewTaskNo,
                visitNo,
                operator);
    }

    /** 医嘱作废：状态机迁移 CANCELLED（EXECUTING 起拒）+ cancelled 事件（M05 撤未执行执行单）。 */
    @Override
    @Transactional
    public void cancel(String orderNo, String reason) {
        MedicalOrder order = requireOrder(orderNo);
        long operator = parseOperatorAsEmployeeId();
        // 状态机裁决：仅未产生执行（AUDITED/TRANSFERRED）可达 CANCELLED——已执行医嘱拒 IP-1010
        stateMachine.transition(order, OrderStatus.CANCELLED, reason, operator);
        OffsetDateTime cancelledAt = OffsetDateTime.now();
        String visitNo = visitNoOf(order.getVisitId());
        // 作废事件（V800 id 45 载荷）：M05 撮此撤销未执行执行单并拦截在途核对（执行单撤销归 M05）
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_ORDER_CANCELLED,
                new OrderCancelledPayload(orderNo, visitNo, order.getPatientId(), cancelledAt.toInstant(), reason)));
        log.info(
                "医嘱作废完成：orderNo={}，visitNo={}，patientId={}，reason={}，operator={}",
                orderNo,
                visitNo,
                order.getPatientId(),
                reason,
                operator);
    }

    /** 撤回重审：AUDITED→CREATED（仅转抄前）+ 生效时点复位 + revoked 事件（无订阅方，登记保证契约完整）。 */
    @Override
    @Transactional
    public void revokeAudit(String orderNo) {
        MedicalOrder order = requireOrder(orderNo);
        long operator = parseOperatorAsEmployeeId();
        // 状态机裁决：仅 AUDITED 可达 CREATED（撤回重审）；TRANSFERRED 起已有转抄执行面拒 IP-1010
        stateMachine.transition(order, OrderStatus.CREATED, "撤回审核（转抄前）", operator);
        // 生效时点复位（撤回后非过审态，begin_at 语义归零——再审核链重新落值）
        if (orderMapper.updateRevokeAudit(orderNo, String.valueOf(operator)) == 0) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT, HttpStatus.CONFLICT, "撤回生效时点复位零行（并发逻辑删窗口）：orderNo=" + orderNo);
        }
        OffsetDateTime revokedAt = OffsetDateTime.now();
        String visitNo = visitNoOf(order.getVisitId());
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_ORDER_REVOKED,
                new OrderRevokedPayload(orderNo, visitNo, order.getPatientId(), revokedAt.toInstant())));
        // TODO(revoke-audit-permission): 撤回审核权限控制（仅开立医生/授权角色可撤），计划于 P3 权限面引入
        log.info("医嘱撤回审核完成：orderNo={}，visitNo={}，operator={}", orderNo, visitNo, operator);
    }

    /** 医嘱重整：只留痕（from=to）不迁移状态；列表顺序即新视图序。 */
    @Override
    @Transactional
    public void reorganize(OrderReorganizeRequest req) {
        InpatientVisit visit = requireVisit(req.visitId());
        long operator = parseOperatorAsEmployeeId();
        OffsetDateTime occurredAt = OffsetDateTime.now();
        for (String orderNo : req.orderNos()) {
            MedicalOrder order = requireOrder(orderNo);
            // 归属校验：重整面限定本就诊医嘱（跨就诊医嘱号混入定性归属冲突）
            if (!order.getVisitId().equals(visit.getId())) {
                throw new BizException(
                        InpatientErrorCode.CONFLICT,
                        HttpStatus.CONFLICT,
                        "重整医嘱归属其他就诊：orderNo=" + orderNo + "，visitId=" + req.visitId());
            }
            // 重整留痕（from=to 无迁移动作——不触状态机，状态面零变更；04 Spec §3.3 冻结语义）
            insertStatusLog(
                    order,
                    order.getStatus(),
                    order.getStatus(),
                    REASON_REORGANIZE,
                    String.valueOf(operator),
                    occurredAt);
        }
        log.info(
                "医嘱重整完成：visitId={}，重排 {} 条（视图序随列表序），operator={}",
                req.visitId(),
                req.orderNos().size(),
                operator);
    }

    /** 口头医嘱补录确认：oral_confirmed_at 落值（状态不迁移——补录确认为审计动作）。 */
    @Override
    @Transactional
    public void oralConfirm(String orderNo) {
        MedicalOrder order = requireOrder(orderNo);
        // 口头医嘱行存在性校验（oral_flag 标记在明细行；非口头医嘱补录确认定性状态面违例）
        Long oralCount = itemMapper.selectCount(Wrappers.<MedicalOrderItem>lambdaQuery()
                .eq(MedicalOrderItem::getOrderId, order.getId())
                .eq(MedicalOrderItem::getOralFlag, true));
        if (oralCount == null || oralCount == 0) {
            throw new BizException(
                    InpatientErrorCode.ORDER_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "非抢救口头医嘱不可补录确认：orderNo=" + orderNo);
        }
        // 重复确认拦截（已确认医嘱禁二次补录——事后限时催办归 P3，本版显式拒绝）
        if (order.getOralConfirmedAt() != null) {
            throw new BizException(
                    InpatientErrorCode.ORDER_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "口头医嘱已补录确认（禁重复确认）：orderNo=" + orderNo);
        }
        // 值面 CAS（oral_confirmed_at IS NULL 限定兜底并发双确认窗口）
        if (orderMapper.updateOralConfirmedAt(orderNo, OffsetDateTime.now(), OperatorContextHolder.get()) == 0) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT, HttpStatus.CONFLICT, "补录确认落写零行（并发确认窗口）：orderNo=" + orderNo);
        }
        log.info("抢救口头医嘱补录确认完成：orderNo={}，operator={}", orderNo, OperatorContextHolder.get());
    }

    /**
     * 过审副作用收口（系统自动过审与药师通过回执同链）：生效时点落值 + 审计行 + audited 子键事件。
     *
     * @param order        过审医嘱行（status 已迁移 AUDITED），非空
     * @param orderType    医嘱类型（词表内已裁决），非空
     * @param stage        审核阶段（SYSTEM/PHARMACIST），非空
     * @param reviewTaskNo 审方任务单号（PHARMACIST 行携带；SYSTEM 行 null），可空
     * @param reason       审计理由，非空
     * @param operator     审核操作者员工 ID，非空
     * @param auditedAt    审核通过时点，非空
     */
    private void applyAuditedSideEffects(
            MedicalOrder order,
            OrderType orderType,
            AuditStage stage,
            String reviewTaskNo,
            String reason,
            long operator,
            OffsetDateTime auditedAt) {
        // 生效时点落值（V904 begin_at 契约：审核通过面写入；0 行=并发逻辑删窗口定性冲突）
        if (orderMapper.updateAuditBegin(order.getOrderNo(), auditedAt, String.valueOf(operator)) == 0) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "审核生效时点落写零行（并发逻辑删窗口）：orderNo=" + order.getOrderNo());
        }
        insertAuditRow(order, stage, CONCLUSION_PASSED, reviewTaskNo, reason, String.valueOf(operator), auditedAt);
        publishAudited(order, orderType, stage, String.valueOf(operator), auditedAt.toInstant());
    }

    /**
     * 发布审核通过事件（routing key 携带类型子键——audited.lab/audited.drug 等，登记名不带子键
     * R3-06；M13 全量消费 inpatient.order.# 即时计价）。载荷仅定位键/审核方/时间线，禁敏感明文。
     *
     * @param order     过审医嘱行，非空
     * @param orderType 医嘱类型（子键来源），非空
     * @param stage     审核阶段（auditType 载荷值），非空
     * @param operator  审核操作者员工 ID string，非空
     * @param auditedAt 审核通过时点，非空
     */
    private void publishAudited(
            MedicalOrder order, OrderType orderType, AuditStage stage, String operator, Instant auditedAt) {
        String visitNo = visitNoOf(order.getVisitId());
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.withTypeKey(
                        InpatientMessagingConstants.EVENT_ORDER_AUDITED, orderType.subKey()),
                new OrderAuditedPayload(
                        order.getOrderNo(), visitNo, order.getPatientId(), stage.getCode(), operator, auditedAt)));
    }

    /**
     * 审核流水行落库（只增）：审计五列由操作者回填。
     *
     * @param order       审核对象医嘱行，非空
     * @param stage       审核阶段，非空
     * @param conclusion  审核结论（PASSED/REJECTED），非空
     * @param reviewTaskNo 审方任务单号（可空——SYSTEM 行）
     * @param reason      审核理由（驳回=药师意见），非空
     * @param operator    审核操作者员工 ID string，非空
     * @param occurredAt  审核发生时点，非空
     */
    private void insertAuditRow(
            MedicalOrder order,
            AuditStage stage,
            String conclusion,
            String reviewTaskNo,
            String reason,
            String operator,
            OffsetDateTime occurredAt) {
        OrderAudit row = new OrderAudit();
        row.setOrderId(order.getId());
        row.setStage(stage.getCode());
        row.setReviewTaskNo(reviewTaskNo);
        row.setConclusion(conclusion);
        row.setReason(reason);
        row.setAuditOperator(operator);
        row.setOccurredAt(occurredAt);
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        // 数据库写操作：审核流水只增落库（V905 order_audit）
        auditMapper.insert(row);
    }

    /**
     * 状态日志行落库（只增——重整留痕直写面；迁移留痕归状态机自动落库，本方法不承载迁移场景）。
     *
     * @param order      留痕对象医嘱行，非空
     * @param fromStatus 留痕前状态（重整=当前态），非空
     * @param toStatus   留痕后状态（重整=当前态，from=to 表示无迁移动作），非空
     * @param reason     留痕原因，非空
     * @param operator   操作者员工 ID string，非空
     * @param occurredAt 发生时点，非空
     */
    private void insertStatusLog(
            MedicalOrder order,
            String fromStatus,
            String toStatus,
            String reason,
            String operator,
            OffsetDateTime occurredAt) {
        OrderStatusLog row = new OrderStatusLog();
        row.setOrderId(order.getId());
        row.setFromStatus(fromStatus);
        row.setToStatus(toStatus);
        row.setReason(reason);
        row.setOperator(operator);
        row.setOccurredAt(occurredAt);
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        // 数据库写操作：状态日志只增落库（V905 order_status_log）
        statusLogMapper.insert(row);
    }

    /** 按医嘱号定位行（未命中定性 IP-1009；逻辑删由 @TableLogic 自动过滤）。 */
    private MedicalOrder requireOrder(String orderNo) {
        MedicalOrder order =
                orderMapper.selectOne(Wrappers.<MedicalOrder>lambdaQuery().eq(MedicalOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(InpatientErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND, "医嘱不存在：" + orderNo);
        }
        return order;
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

    /** 就诊主键→I 型号映射（事件载荷 visitId 号口径；未命中定性 IP-1007 数据不一致）。 */
    private String visitNoOf(Long visitPk) {
        InpatientVisit visit = visitMapper.selectById(visitPk);
        if (visit == null) {
            throw new BizException(
                    InpatientErrorCode.VISIT_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "医嘱关联住院就诊不存在（数据不一致）：visitId(pk)=" + visitPk);
        }
        return visit.getVisitId();
    }

    /**
     * 医嘱类型词表裁决（词表外=主数据脏数据，审核链无法裁决用药面——fail-closed 拒 IP-1023）。
     *
     * @param order 待裁决医嘱行，非空
     * @return 词表内医嘱类型，非空
     */
    private OrderType requireOrderType(MedicalOrder order) {
        OrderType orderType = OrderType.fromCode(order.getOrderType());
        if (orderType == null) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "医嘱类型词表外脏数据（审核链无法裁决）：orderNo=" + order.getOrderNo() + "，orderType=" + order.getOrderType());
        }
        return orderType;
    }

    /**
     * 操作者标识解析为员工 ID（REST 面操作者上下文；缺失/非数字显式 IP-1022 拒绝——
     * W-22⑦ 禁裸 parse 同款守卫）。
     *
     * @return 员工 ID（OperatorContextHolder 运行态 userId 数字形态）
     * @throws BizException IP-1022 操作者标识缺失或非数字时触发
     */
    private static long parseOperatorAsEmployeeId() {
        String operator = OperatorContextHolder.get();
        if (operator == null || !operator.matches("\\d+")) {
            throw new BizException(
                    InpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "操作者标识缺失或非数字（无法定位审核操作主体）：" + maskOperator(operator));
        }
        return Long.parseLong(operator);
    }

    /**
     * 回执操作者宽松解析（M06 回执线程无操作者上下文，操作者取回执载荷）：非数字/缺失容错为 0
     * 记录（回执结论与状态迁移不因操作者标识异常而丢失——仅告警留痕）。
     *
     * @param auditOperator 回执载荷 auditOperator（审方药师员工 ID），可空；来源：M06 回执
     * @return 员工 ID 数字形态（异常载荷容错 0）
     */
    private static long parseReplyOperator(String auditOperator) {
        if (auditOperator == null || !auditOperator.matches("\\d+")) {
            log.warn("审方回执操作者标识缺失或非数字（容错 0 记录）：auditOperator={}", maskOperator(auditOperator));
            return 0L;
        }
        return Long.parseLong(auditOperator);
    }

    /** 回执时点 Instant→OffsetDateTime 转换（UTC 语义承载，DB TIMESTAMPTZ 存储）。 */
    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 工号脱敏（等保三级口径，禁明文工号出 ProblemDetail/日志）：首尾各留 1 位，中段 ***。 */
    private static String maskOperator(String operator) {
        if (operator == null || operator.length() <= 2) {
            return "***";
        }
        return operator.charAt(0) + "***" + operator.charAt(operator.length() - 1);
    }
}
