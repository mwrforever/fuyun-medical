package com.fuyun.inpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.OrderCreatedItem;
import com.fuyun.inpatient.api.payload.OrderCreatedPayload;
import com.fuyun.inpatient.api.payload.OrderStoppedPayload;
import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.OrderCreateRequest;
import com.fuyun.inpatient.dto.OrderItemRequest;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.entity.MedicalOrder;
import com.fuyun.inpatient.entity.MedicalOrderItem;
import com.fuyun.inpatient.entity.OrderFrequency;
import com.fuyun.inpatient.enums.OrderClass;
import com.fuyun.inpatient.enums.OrderStatus;
import com.fuyun.inpatient.enums.OrderType;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.mapper.MedicalOrderItemMapper;
import com.fuyun.inpatient.mapper.MedicalOrderMapper;
import com.fuyun.inpatient.mapper.OrderFrequencyMapper;
import com.fuyun.inpatient.service.MedicalOrderService;
import com.fuyun.inpatient.service.OrderStateMachineService;
import com.fuyun.inpatient.vo.MedicalOrderVO;
import com.fuyun.inpatient.vo.OrderDetailVO;
import com.fuyun.inpatient.vo.OrderItemVO;
import com.fuyun.patient.api.AllergyChecker;
import com.fuyun.patient.api.AllergyItem;
import com.fuyun.system.api.PracticeCheckPort;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 住院医嘱域服务实现（V904 三表业务面）。开立守卫链（GC20 四层顺序冻结）：L1 执业授权
 * （PracticeCheckPort，开单类=PRESCRIPTION——pharmacy 开方链同口径；未过 IP-1012 403，
 * 文案工号脱敏）→ L2 过敏强阳性（AllergyChecker 有效过敏项，仅药品行 item_code 命中
 * 过敏物 code 拦截 IP-1013 409）→ L3 明细/频次有效性（药品行剂量+单位+途径必填、长期
 * 必携频次 IP-1011；频次字典无命中 IP-1021）→ L4 嘱托限定（standby 仅 LONG，违者
 * IP-1012 前置的 IP-1022）。主子表同事务落库（CREATED）后事务内发布 order.created
 * （routing key 携带类型子键——drug 子键驱动 M06 审方任务生成）。
 * 停嘱面（stop/stopAllForTransfer 共用 stopInternal）：状态机迁移 STOPPED（唯一执行面
 * OrderStateMachineService，合法态 AUDITED/TRANSFERRED/EXECUTING）+ 停嘱时点/原因落值 +
 * 未来计划批量作废（数据面归 Task 7 V906 回接）+ stopped 事件（V800 id 44 载荷）。
 * 转科停嘱失败异常传播整体回滚编排事务（接口契约）。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class MedicalOrderServiceImpl implements MedicalOrderService {

    /** 执业授权类型：开单类（pharmacy 开方链同口径——PracticeCheckPort grantType 词表首值） */
    private static final String GRANT_PRESCRIPTION = "PRESCRIPTION";

    /** 转科批量停嘱合法态集（04 Spec §3.3 可停态；CREATED/AUDIT_REJECTED/终态不可停） */
    private static final List<String> STOPPABLE_STATUSES =
            List.of(OrderStatus.AUDITED.getCode(), OrderStatus.TRANSFERRED.getCode(), OrderStatus.EXECUTING.getCode());

    /** 行项目类型：药品（过敏拦截与剂量/途径必填校验的判定口径） */
    private static final String ITEM_TYPE_DRUG = "DRUG";

    private final MedicalOrderMapper orderMapper;

    private final MedicalOrderItemMapper itemMapper;

    private final OrderFrequencyMapper frequencyMapper;

    private final InpatientVisitMapper visitMapper;

    private final InpatientSeqGate seqGate;

    private final PracticeCheckPort practiceCheckPort;

    private final AllergyChecker allergyChecker;

    private final OrderStateMachineService stateMachine;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 InpatientWebConfig @Import——Task 5 落地后闭合 Task 4 预注入的
     * TransferServiceImpl 医嘱停嘱装配链）。
     *
     * @param orderMapper       医嘱主表 mapper，非空
     * @param itemMapper        医嘱明细 mapper，非空
     * @param frequencyMapper   频次字典 mapper，非空；频次有效性校验（IP-1021）
     * @param visitMapper       住院就诊 mapper，非空；在院校验与 visitId 号映射
     * @param seqGate           住院业务号发号器（MO 医嘱号），非空
     * @param practiceCheckPort 执业授权校验端口（system api），非空；L1 承载
     * @param allergyChecker    过敏项校验端口（patient api），非空；L2 承载
     * @param stateMachine      医嘱状态机服务（状态迁移唯一执行面），非空
     * @param events            进程内事件发布器（AFTER_COMMIT 出 MQ），非空
     */
    public MedicalOrderServiceImpl(
            MedicalOrderMapper orderMapper,
            MedicalOrderItemMapper itemMapper,
            OrderFrequencyMapper frequencyMapper,
            InpatientVisitMapper visitMapper,
            InpatientSeqGate seqGate,
            PracticeCheckPort practiceCheckPort,
            AllergyChecker allergyChecker,
            OrderStateMachineService stateMachine,
            ApplicationEventPublisher events) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.frequencyMapper = frequencyMapper;
        this.visitMapper = visitMapper;
        this.seqGate = seqGate;
        this.practiceCheckPort = practiceCheckPort;
        this.allergyChecker = allergyChecker;
        this.stateMachine = stateMachine;
        this.events = events;
    }

    /**
     * 医嘱开立：守卫链（在院 → 四层校验）→ 发号落库（CREATED）→ 事务内发布 order.created。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空
     * @param req     开立入参，非空
     * @return 开立后医嘱出参（status=CREATED），非空
     * @throws BizException IP-1007/IP-1008/IP-1012/IP-1013/IP-1011/IP-1021/IP-1022/IP-1023（接口注全清单）
     */
    @Override
    @Transactional
    public MedicalOrderVO create(String visitId, OrderCreateRequest req) {
        // 守卫链⓪：就诊定位与在院态校验（出院/作废就诊禁开立）
        InpatientVisit visit = requireVisit(visitId);
        if (!VisitStatus.ADMITTED.getCode().equals(visit.getStatus())) {
            throw new BizException(
                    InpatientErrorCode.VISIT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "住院就诊状态不允许开立医嘱：visitId=" + visitId + "，当前状态=" + visit.getStatus());
        }
        // 医嘱类型/分类词表校验（服务面覆盖模块内直调场景，Web 层由 @Pattern 兜底）
        OrderType orderType = OrderType.fromCode(req.orderType());
        if (orderType == null) {
            throw paramInvalid("orderType", req.orderType());
        }
        OrderClass orderClass = OrderClass.fromCode(req.orderClass());
        if (orderClass == null) {
            throw paramInvalid("orderClass", req.orderClass());
        }
        // 四层校验 L1→L4（GC20 顺序冻结；任何一层拒绝即整单不入库）
        long employeeId = checkPracticeGrant();
        checkAllergyConflicts(visit.getPatientId(), req.items());
        checkItemsAndFrequency(req, orderClass);
        checkStandbyOnlyForLong(req, orderClass);
        // 发号：MO 医嘱号（InpatientSeqGate 唯一取号出口）；成组组号缺省回填本医嘱号（单条自成一组）
        String orderNo = seqGate.nextNo("MO");
        String operator = OperatorContextHolder.get();
        MedicalOrder order = new MedicalOrder();
        order.setOrderNo(orderNo);
        order.setVisitId(visit.getId());
        order.setPatientId(visit.getPatientId());
        order.setOrderType(orderType.getCode());
        order.setOrderClass(orderClass.getCode());
        order.setStandbyFlag(Boolean.TRUE.equals(req.standbyFlag()));
        order.setGroupNo(req.groupNo() == null || req.groupNo().isBlank() ? orderNo : req.groupNo());
        order.setFreqCode(req.freqCode());
        order.setDoctorId(operator);
        order.setOrderedAt(OffsetDateTime.now());
        order.setStatus(OrderStatus.CREATED.getCode());
        order.setCreatedBy(operator);
        order.setUpdatedBy(operator);
        try {
            // 数据库写操作：医嘱主表落库（uk_medical_order_no 兜底发号幂等）
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            throw new BizException(InpatientErrorCode.CONFLICT, HttpStatus.CONFLICT, "医嘱号唯一冲突（幂等拒绝）：" + orderNo);
        }
        // 子表逐行落库：item_seq 按列表序 1 起递增（服务层单一写入口保证组内唯一），布尔缺省回填
        List<MedicalOrderItem> savedItems = saveItems(order, req.items(), operator);
        // 事务内发布开立事件（AFTER_COMMIT 出 fy.topic；routing key=inpatient.order.created.<子键>，
        // drug 子键驱动 M06 审方任务生成——DomainEventSender 的 eventType 双作信封类型与路由键）；
        // 载荷携 items 名称快照（药品通用名计价需要），禁患者姓名/诊断文本
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.withTypeKey(
                        InpatientMessagingConstants.EVENT_ORDER_CREATED, orderType.subKey()),
                new OrderCreatedPayload(
                        orderNo,
                        visitId,
                        visit.getPatientId(),
                        orderType.subKey(),
                        orderClass.getCode(),
                        Boolean.TRUE.equals(req.standbyFlag()),
                        order.getGroupNo(),
                        req.freqCode(),
                        savedItems.stream()
                                .map(item -> new OrderCreatedItem(
                                        item.getItemSeq(),
                                        item.getItemCode(),
                                        item.getNameSnapshot(),
                                        joinDosage(item),
                                        item.getDosageUnit(),
                                        item.getRoute(),
                                        item.getQuantity().toPlainString(),
                                        // 行项目类型与头 orderType 同口径（routing 子键小写形态——
                                        // L3 已过词表校验，fromCode 非空安全）
                                        OrderType.fromCode(item.getItemType()).subKey()))
                                .toList())));
        log.info(
                "医嘱开立完成：orderNo={}，visitId={}，patientId={}，orderType={}，orderClass={}，freqCode={}，明细 {} 行，operator={}",
                orderNo,
                visitId,
                visit.getPatientId(),
                orderType.getCode(),
                orderClass.getCode(),
                req.freqCode(),
                savedItems.size(),
                operator);
        return MedicalOrderVO.from(order, visitId);
    }

    /**
     * 医嘱分页查询：按就诊过滤（必填键），分类可叠加；开立时间倒序（最新在前）。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空
     * @param clazz   医嘱分类过滤，可空
     * @param page    页码（0 基），非负
     * @param size    单页条数，正
     * @return 医嘱分页出参，非空
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<MedicalOrderVO> list(String visitId, OrderClass clazz, int page, int size) {
        // 查询键校验（就诊号必填——住院医嘱视图以就诊为轴）兼出参 visitId 号映射取数
        InpatientVisit visit = requireVisit(visitId);
        // 分类条件缺席即全分类（null.getCode() 惰性求值防护：先取值再进条件）
        String classCode = clazz == null ? null : clazz.getCode();
        // 数据库读操作：分页（0 基请求转 MP 1 基 current；Wrappers 直构范式同 AdmissionServiceImpl）
        Page<MedicalOrder> result = orderMapper.selectPage(
                new Page<>(page + 1, size),
                Wrappers.<MedicalOrder>lambdaQuery()
                        .eq(MedicalOrder::getVisitId, visit.getId())
                        .eq(classCode != null, MedicalOrder::getOrderClass, classCode)
                        .orderByDesc(MedicalOrder::getOrderedAt));
        return PageResult.of(
                result.getRecords().stream()
                        .map(order -> MedicalOrderVO.from(order, visitId))
                        .toList(),
                page,
                size,
                result.getTotal());
    }

    /**
     * 医嘱详情：头 + 明细行全集（item_seq 升序——闭环追溯取数入口）。
     *
     * @param orderNo 医嘱号，非空
     * @return 医嘱详情出参（含项），非空
     * @throws BizException IP-1009 医嘱不存在/IP-1007 关联就诊不存在
     */
    @Override
    @Transactional(readOnly = true)
    public OrderDetailVO detail(String orderNo) {
        MedicalOrder order = requireOrder(orderNo);
        // 出参 visitId 号映射（表内存 inpatient_visit 主键，出参转 I 型 14 位号）
        InpatientVisit visit = visitMapper.selectById(order.getVisitId());
        if (visit == null) {
            throw new BizException(
                    InpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "医嘱关联住院就诊不存在（数据不一致）：" + orderNo);
        }
        // 数据库读操作：明细行全集（item_seq 升序）
        List<OrderItemVO> items = itemMapper
                .selectList(Wrappers.<MedicalOrderItem>lambdaQuery()
                        .eq(MedicalOrderItem::getOrderId, order.getId())
                        .orderByAsc(MedicalOrderItem::getItemSeq))
                .stream()
                .map(OrderItemVO::from)
                .toList();
        return new OrderDetailVO(MedicalOrderVO.from(order, visit.getVisitId()), items);
    }

    /**
     * 医嘱停嘱（Task 6 端点消费）：与 stopAllForTransfer 共用 stopInternal。
     *
     * @param orderNo 医嘱号，非空
     * @param reason  停嘱原因，非空
     * @throws BizException IP-1009/IP-1010/IP-1022（操作者标识非数字）
     */
    @Override
    @Transactional
    public void stop(String orderNo, String reason) {
        MedicalOrder order = requireOrder(orderNo);
        stopInternal(order, reason);
    }

    /**
     * 转科自动停嘱（转科编排阶段①，编排事务内）：该就诊全部长期可停医嘱逐条停嘱——
     * 逐条经状态机（合法迁移表唯一裁决面）保证与 Task 6 单条停嘱同一状态语义；任一条
     * 迁移失败（并发窗口）异常传播整体回滚编排事务。
     *
     * @param visitId 住院就诊主键（inpatient_visit.id），非空
     * @param reason  停嘱原因（转科固定文案「转科」），非空
     * @throws BizException IP-1007 就诊不存在/IP-1010 停嘱链状态机违例/IP-1022 操作者标识非数字
     */
    @Override
    @Transactional
    public void stopAllForTransfer(Long visitId, String reason) {
        // 就诊定位（出参/事件 visitId 号映射面；不存在定性 IP-1007——编排调用前已校验，此处防御）
        InpatientVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(
                    InpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "住院就诊不存在：visitId(pk)=" + visitId);
        }
        // 转科停嘱面：长期（order_class=long）且处可停态（AUDITED/TRANSFERRED/EXECUTING）全集；
        // CREATED/AUDIT_REJECTED 停留中的医嘱不在停嘱面（状态机合法迁移表无 CREATED→STOPPED 边，
        // 04 Spec §3.3 冻结），转科后由审核链自然收敛
        List<MedicalOrder> ongoing = orderMapper.selectList(Wrappers.<MedicalOrder>lambdaQuery()
                .eq(MedicalOrder::getVisitId, visitId)
                .eq(MedicalOrder::getOrderClass, OrderClass.LONG.getCode())
                .in(MedicalOrder::getStatus, STOPPABLE_STATUSES));
        if (ongoing.isEmpty()) {
            log.info("转科批量停嘱：无可停长期医嘱（空集直过）：visitId(pk)={}，operator={}", visitId, OperatorContextHolder.get());
            return;
        }
        for (MedicalOrder order : ongoing) {
            stopInternal(order, reason);
        }
        log.info(
                "转科批量停嘱完成：visitId(pk)={}，visitNo={}，停嘱 {} 条，reason={}，operator={}",
                visitId,
                visit.getVisitId(),
                ongoing.size(),
                reason,
                OperatorContextHolder.get());
    }

    /**
     * 停嘱共用实现（stop/stopAllForTransfer）：状态机迁移 STOPPED → 停嘱值面落值 → 未来计划
     * 作废留痕（数据面归 Task 7 回接）→ 事务内发布 stopped 事件。
     *
     * @param order  停嘱医嘱行（status 为停嘱前实态），非空
     * @param reason 停嘱原因，非空
     * @throws BizException IP-1010 停嘱状态机违例/IP-1022 操作者标识非数字
     */
    private void stopInternal(MedicalOrder order, String reason) {
        long operator = parseOperatorAsEmployeeId();
        // 状态机迁移 STOPPED（合法态裁决 + CAS 唯一执行面；CREATED/AUDIT_REJECTED/终态拒 IP-1010）
        stateMachine.transition(order, OrderStatus.STOPPED, reason, operator);
        // 停嘱值面补写（非状态面）：停嘱时点=服务器时间、停嘱原因（0 行=并发逻辑删窗口，定性冲突）
        OffsetDateTime stoppedAt = OffsetDateTime.now();
        if (orderMapper.updateStopValues(order.getOrderNo(), stoppedAt, reason, String.valueOf(operator)) == 0) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "停嘱值面落写零行（并发逻辑删窗口）：orderNo=" + order.getOrderNo());
        }
        // 未来执行计划批量作废（数据面归 Task 7 V906 建表后回接——调用点留痕）
        cancelFuturePlans(order, stoppedAt);
        // 事务内发布停嘱事件（AFTER_COMMIT 出 fy.topic；M05 撮此撤销未执行执行单、M13 按停嘱时点
        // 截断持续性费用）；载荷仅定位键/时间线/原因，禁患者姓名/诊断文本
        String visitNo = visitNoOf(order.getVisitId());
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_ORDER_STOPPED,
                new OrderStoppedPayload(
                        order.getOrderNo(),
                        visitNo,
                        order.getPatientId(),
                        stoppedAt.toInstant(),
                        String.valueOf(operator),
                        reason)));
        log.info(
                "医嘱停嘱完成：orderNo={}，visitNo={}，patientId={}，reason={}，operator={}",
                order.getOrderNo(),
                visitNo,
                order.getPatientId(),
                reason,
                operator);
    }

    /**
     * 未来执行计划批量作废（PENDING 计划置 CANCELLED）：order_execute_plan 表归 Task 7 V906
     * 建表落盘，本版本以日志留痕承载调用点——建表后在本方法内补批量作废 SQL（调用点与签名
     * 冻结零改动）。protected 形态供 Task 7 回接时覆写扩展。
     *
     * @param order     停嘱医嘱行（状态已 STOPPED），非空
     * @param stoppedAt 停嘱时点（计划作废时间线基准——仅作废该时点后的未执行计划），非空
     */
    // TODO(order-execute-plan): 未来计划批量作废 SQL，计划于 Task 7 V906 建表后回接
    protected void cancelFuturePlans(MedicalOrder order, OffsetDateTime stoppedAt) {
        log.info("停嘱联动计划作废（数据面归 Task 7 V906 回接，当前留痕）：orderNo={}，stoppedAt={}", order.getOrderNo(), stoppedAt);
    }

    /**
     * 四层校验 L1：开立执业授权（开单类 PRESCRIPTION——pharmacy 开方链同口径）；未过拒
     * IP-1012（403），文案携工号脱敏（等保三级脱敏口径，禁明文工号出 ProblemDetail）。
     *
     * @return 开立医生员工 ID（数字形态，后续状态迁移操作者复用）
     * @throws BizException IP-1022 操作者标识缺失/非数字；IP-1012 授权未过
     */
    private long checkPracticeGrant() {
        long employeeId = parseOperatorAsEmployeeId();
        // 第三方接口调用：system 执业授权校验（开单类）
        if (!practiceCheckPort.check(employeeId, GRANT_PRESCRIPTION).passed()) {
            log.warn(
                    "医嘱开立被拒（执业授权未过）：employeeId（脱敏）={}，grantType={}",
                    maskOperator(String.valueOf(employeeId)),
                    GRANT_PRESCRIPTION);
            throw new BizException(
                    InpatientErrorCode.PRACTICE_FORBIDDEN,
                    HttpStatus.FORBIDDEN,
                    "医嘱开立执业授权未过（工号 " + maskOperator(String.valueOf(employeeId)) + "）：请联系医务授权管理");
        }
        return employeeId;
    }

    /**
     * 四层校验 L2：过敏强阳性拦截（IP-1013，409）——判定口径：仅药品行（itemType=DRUG）且
     * item_code 命中患者有效过敏项的过敏物 code（AllergyItem.itemCode 非空项——手工录入
     * 无字典对照项不参与 code 匹配）；命中即整单拒绝（强阳性禁忌，禁软警告放行）。
     *
     * @param patientId 患者主索引，非空
     * @param items     开立明细行，非空
     * @throws BizException IP-1013 药品行命中过敏物 code 时触发
     */
    private void checkAllergyConflicts(long patientId, List<OrderItemRequest> items) {
        // 第三方接口调用：patient 有效过敏项清单（无过敏为空清单）
        List<AllergyItem> allergies = allergyChecker.listActiveAllergies(patientId);
        if (allergies.isEmpty()) {
            return;
        }
        // 过敏物 code 索引（code→过敏物名称，拦截文案定位用；空 code 项跳过）
        Set<String> allergyCodes = allergies.stream()
                .map(AllergyItem::itemCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toSet());
        for (OrderItemRequest item : items) {
            if (ITEM_TYPE_DRUG.equals(item.itemType()) && allergyCodes.contains(item.itemCode())) {
                log.warn(
                        "医嘱开立被拒（过敏强阳性）：patientId={}，itemCode={}，itemName={}",
                        patientId,
                        item.itemCode(),
                        item.itemName());
                throw new BizException(
                        InpatientErrorCode.ALLERGY_CONFLICT,
                        HttpStatus.CONFLICT,
                        "医嘱开立过敏强阳性拦截：项目 " + item.itemName() + "（" + item.itemCode() + "）命中患者有效过敏项，禁止开立");
            }
        }
    }

    /**
     * 四层校验 L3：明细/频次有效性——药品行剂量+单位+途径必填（缺任一拒 IP-1011）、长期医嘱
     * 必携频次（缺拒 IP-1011）、频次编码须在 order_frequency 字典命中（无命中拒 IP-1021）。
     *
     * @param req        开立入参，非空
     * @param orderClass 医嘱分类（已过词表校验），非空
     * @throws BizException IP-1011 药品行剂量/途径缺失或长期缺频次；IP-1021 频次字典无命中
     */
    private void checkItemsAndFrequency(OrderCreateRequest req, OrderClass orderClass) {
        for (OrderItemRequest item : req.items()) {
            // 行项目类型词表校验（与 OrderType 同词表；Web 层 @Pattern 兜底）
            if (OrderType.fromCode(item.itemType()) == null) {
                throw paramInvalid("itemType", item.itemType());
            }
            // 药品行三必填：剂量/单位/途径（静滴滴速可选）
            if (ITEM_TYPE_DRUG.equals(item.itemType())) {
                if (isBlank(item.dosage()) || isBlank(item.dosageUnit()) || isBlank(item.route())) {
                    throw new BizException(
                            InpatientErrorCode.ORDER_ITEM_INVALID,
                            HttpStatus.BAD_REQUEST,
                            "药品医嘱明细剂量/单位/途径必填：itemCode=" + item.itemCode()
                                    + "，dosage=" + item.dosage() + "，dosageUnit=" + item.dosageUnit()
                                    + "，route=" + item.route());
                }
            }
        }
        // 长期医嘱必携频次（临时医嘱无频次——单次即刻执行）
        if (orderClass == OrderClass.LONG && isBlank(req.freqCode())) {
            throw new BizException(
                    InpatientErrorCode.ORDER_ITEM_INVALID, HttpStatus.BAD_REQUEST, "长期医嘱必须携带频次编码（freqCode）");
        }
        // 频次字典命中校验（携带即校验——临时医嘱理论不携，携带脏值同拒）
        if (!isBlank(req.freqCode())) {
            // 数据库读操作：频次字典命中查询（uk_order_frequency_code 唯一索引）
            OrderFrequency frequency = frequencyMapper.selectOne(
                    Wrappers.<OrderFrequency>lambdaQuery().eq(OrderFrequency::getFreqCode, req.freqCode()));
            if (frequency == null) {
                throw new BizException(
                        InpatientErrorCode.FREQUENCY_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "医嘱频次不存在（order_frequency 无命中）：" + req.freqCode());
            }
        }
    }

    /**
     * 四层校验 L4：嘱托限定——备用嘱（standby）仅长期医嘱可用（长期按需执行语义），
     * 临时+嘱托结构矛盾拒 IP-1022。
     *
     * @param req        开立入参，非空
     * @param orderClass 医嘱分类（已过词表校验），非空
     * @throws BizException IP-1022 STAT+standby 结构矛盾时触发
     */
    private void checkStandbyOnlyForLong(OrderCreateRequest req, OrderClass orderClass) {
        if (Boolean.TRUE.equals(req.standbyFlag()) && orderClass != OrderClass.LONG) {
            throw new BizException(
                    InpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "备用嘱（嘱托）仅长期医嘱可用：orderClass=" + orderClass.getCode());
        }
    }

    /**
     * 子表逐行落库：item_seq 按列表序 1 起递增（服务层生成，禁前端错序），布尔缺省回填 false。
     *
     * @param order    医嘱主表行（已落库，id 已回填），非空
     * @param items    明细入参，非空
     * @param operator 操作者（审计留痕），非空
     * @return 落库明细行全集（载荷构造复用），非空
     */
    private List<MedicalOrderItem> saveItems(MedicalOrder order, List<OrderItemRequest> items, String operator) {
        List<MedicalOrderItem> saved = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            OrderItemRequest req = items.get(i);
            MedicalOrderItem row = new MedicalOrderItem();
            row.setOrderId(order.getId());
            // 行序号：列表序 1 起递增（成组医嘱组内序号同源；下标驱动——record 值相等场景
            // indexOf 会回卷首个等值行导致序号重复）
            row.setItemSeq(i + 1);
            row.setContinueFlag(Boolean.TRUE.equals(req.continueFlag()));
            row.setItemType(req.itemType());
            row.setItemCode(req.itemCode());
            row.setNameSnapshot(req.itemName());
            row.setDosage(req.dosage());
            row.setDosageUnit(req.dosageUnit());
            row.setRoute(req.route());
            row.setDripRate(req.dripRate());
            row.setQuantity(req.quantity());
            row.setExecDeptId(req.execDeptId());
            row.setSkinTestFlag(Boolean.TRUE.equals(req.skinTestFlag()));
            row.setOralFlag(Boolean.TRUE.equals(req.oralFlag()));
            row.setFeePriced(false);
            row.setFeeStopped(false);
            row.setCreatedBy(operator);
            row.setUpdatedBy(operator);
            // 数据库写操作：明细行落库（与主表同事务成败与共）
            itemMapper.insert(row);
            saved.add(row);
        }
        return saved;
    }

    /** 剂量拼串（载荷 dosage「数值+单位拼串形态，如 0.5g」）：剂量与单位拼接，缺侧原样返回。 */
    private static String joinDosage(MedicalOrderItem item) {
        if (isBlank(item.getDosage())) {
            return item.getDosage();
        }
        return isBlank(item.getDosageUnit()) ? item.getDosage() : item.getDosage() + item.getDosageUnit();
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

    /** 按医嘱号定位行（未命中定性 IP-1009；逻辑删由 @TableLogic 自动过滤）。 */
    private MedicalOrder requireOrder(String orderNo) {
        MedicalOrder order =
                orderMapper.selectOne(Wrappers.<MedicalOrder>lambdaQuery().eq(MedicalOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(InpatientErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND, "医嘱不存在：" + orderNo);
        }
        return order;
    }

    /** 就诊主键→I 型号映射（事件载荷/出参 visitId 号口径；未命中定性 IP-1007 数据不一致）。 */
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
     * 操作者标识解析为员工 ID（运行态 userId 数字直作 employeeId——pharmacy 开方链同口径）：
     * 缺失/非数字显式 IP-1022 拒绝（W-22⑦ 禁裸 parse——NumberFormatException 裸抛即底层异常）。
     *
     * @return 员工 ID（OperatorContextHolder 运行态 userId 数字形态）
     * @throws BizException IP-1022 操作者标识缺失或非数字（无法定位执业授权主体）时触发
     */
    private static long parseOperatorAsEmployeeId() {
        String operator = OperatorContextHolder.get();
        if (operator == null || !operator.matches("\\d+")) {
            throw new BizException(
                    InpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "操作者标识缺失或非数字（无法定位执业授权主体）：" + maskOperator(operator));
        }
        return Long.parseLong(operator);
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
}
