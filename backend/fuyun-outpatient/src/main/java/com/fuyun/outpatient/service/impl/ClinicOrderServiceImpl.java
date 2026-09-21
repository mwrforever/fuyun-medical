package com.fuyun.outpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.billing.api.OutpatientBillingPort;
import com.fuyun.billing.api.VisitFeeView;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OrderCreatedPayload;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.dto.OrderCreateRequest;
import com.fuyun.outpatient.dto.OrderItemRequest;
import com.fuyun.outpatient.entity.ClinicOrder;
import com.fuyun.outpatient.entity.ClinicOrderItem;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.entity.VisitStatusLog;
import com.fuyun.outpatient.enums.OrderStatus;
import com.fuyun.outpatient.enums.OrderType;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.internal.OutpatientDomainEvent;
import com.fuyun.outpatient.mapper.ClinicOrderItemMapper;
import com.fuyun.outpatient.mapper.ClinicOrderMapper;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.outpatient.mapper.VisitStatusLogMapper;
import com.fuyun.outpatient.service.IClinicOrderService;
import com.fuyun.outpatient.service.OutpatientVisitStateMachine;
import com.fuyun.outpatient.vo.ClinicOrderVO;
import com.fuyun.system.api.PracticeCheckPort;
import com.fuyun.system.api.PracticeCheckResult;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 门诊医生站开单服务实现（M03 FU-M03-05，Task 8 写路径唯一入口）：开单五步——visit 终态守卫
 * （红线 5：FINISHED/CANCELLED 后拒绝一切开单）→词表/格式显式校验（W-22⑦ 禁裸 parse：quantity
 * DECIMAL string 与操作者 employeeId 均显式校验 OP-1019）→开单执业授权强校验（统一校验 PRESCRIPTION
 * 处方权，Spec :140，未过 OP-1017 403）→order_no 签发（OP+yyyyMMdd+6 位流水，Redis 当日键 INCR
 * TTL 48h，visit-seq 同型三行直写禁抽象）→CREATED 主单+明细行落库（quantity DECIMAL string 红线）
 * →事务内 publishEvent 发布 order.created（AFTER_COMMIT 出 MQ，A.4.2-7）。作废走状态机 CAS+
 * billing 端口 PENDING 行逐行作废（端口异常一律转译为可读业务错误码，W-20 关联面）；RX_REF 行
 * 引导性 409（作废必须经 M06 作废 API 发起，Spec :119 R2-10）。缴费回执推进 CREATED→PENDING_FEE
 * 与 visit IN_CONSULT→PENDING_FEE（状态机单点+每迁必记，重投幂等=CAS 0 行重读定性跳过）。
 * 资金无涉红线（裁决 7）：本类零金额逻辑。线程安全：无状态单例。装配归 OutpatientWebConfig
 *
 * @Import；com.fuyun.outpatient.service.impl 包 = JaCoCo PACKAGE LINE 1.00 覆盖对象。
 */
@Slf4j
public class ClinicOrderServiceImpl implements IClinicOrderService {

    /** 开单端点可创建的单据类型词表（RX_REF 由 M06 处方生效链写入，不经本端点创建） */
    private static final Set<String> CREATABLE_ORDER_TYPES = Set.of("EXAM", "LAB", "TREATMENT", "DISPOSAL", "MATERIAL");

    /** quantity DECIMAL string 格式（非负十进制，可带小数；D-18 同源，billing 侧 BigDecimal 解析前置防线） */
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("\\d+(\\.\\d+)?");

    /** 开单执业授权类型（Spec :140：医师开检查/检验/治疗/处置单统一校验 PRESCRIPTION 处方权） */
    private static final String PRACTICE_GRANT_PRESCRIPTION = "PRESCRIPTION";

    /** 单号流水键前缀：fy:outpatient:order-seq:{yyyyMMdd}（A.5-1；visit-seq 同型三行直写，禁为两用新建抽象） */
    private static final String ORDER_SEQ_KEY_PREFIX = "fy:outpatient:order-seq:";

    /** 单号流水键 TTL（48h，裁决 11 同源——跨日对账窗口缓冲，禁无过期键） */
    private static final Duration ORDER_SEQ_KEY_TTL = Duration.ofHours(48);

    /** 单号日期段格式（yyyyMMdd，与 visit-seq/appt-seq 同源） */
    private static final DateTimeFormatter SEQ_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** 当日流水 6 位上限（超限即签发 fail-fast） */
    private static final long DAILY_SEQ_CAP = 999999L;

    /** 单号流水段宽度（6 位，CF-5 id 23 契约 order_no 形态） */
    private static final int SEQ_WIDTH = 6;

    /** billing 费用行 PENDING 态 code（可作废词表，与 cancelPendingFee 行态守卫同源） */
    private static final String FEE_STATUS_PENDING = "PENDING";

    private final ClinicOrderMapper clinicOrderMapper;

    private final ClinicOrderItemMapper clinicOrderItemMapper;

    private final VisitMapper visitMapper;

    private final VisitStatusLogMapper visitStatusLogMapper;

    private final PracticeCheckPort practiceCheckPort;

    private final OutpatientBillingPort billingPort;

    private final StringRedisTemplate redisTemplate;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import，backend 宪法 B.1）。
     *
     * @param clinicOrderMapper     申请单主单 mapper，非空；落库/单号定位/状态 CAS
     * @param clinicOrderItemMapper 明细行 mapper，非空；开单明细落库/按单取行
     * @param visitMapper           就诊记录 mapper，非空；开单 visit 终态守卫与缴费回执 visit 推进
     * @param visitStatusLogMapper  迁移日志 mapper，非空；红线 5 每迁必记（IN_CONSULT→PENDING_FEE）
     * @param practiceCheckPort     执业授权校验端口（system api 契约），非空；开单 PRESCRIPTION 强校验
     * @param billingPort           门诊域收费端口（billing api 契约），非空；作废 PENDING 费用行
     * @param redisTemplate         Redis 字符串模板，非空；单号当日流水 INCR 键
     * @param events                Spring 应用事件发布器，非空；事务内发布 order.created
     */
    public ClinicOrderServiceImpl(
            ClinicOrderMapper clinicOrderMapper,
            ClinicOrderItemMapper clinicOrderItemMapper,
            VisitMapper visitMapper,
            VisitStatusLogMapper visitStatusLogMapper,
            PracticeCheckPort practiceCheckPort,
            OutpatientBillingPort billingPort,
            StringRedisTemplate redisTemplate,
            ApplicationEventPublisher events) {
        this.clinicOrderMapper = clinicOrderMapper;
        this.clinicOrderItemMapper = clinicOrderItemMapper;
        this.visitMapper = visitMapper;
        this.visitStatusLogMapper = visitStatusLogMapper;
        this.practiceCheckPort = practiceCheckPort;
        this.billingPort = billingPort;
        this.redisTemplate = redisTemplate;
        this.events = events;
    }

    /**
     * 医生站开单（五步流程，接口 javadoc 契约）：全程同一事务——主单/明细落库与事件发布原子，
     * 执业授权与词表校验任一未过即零写（fail-fast 前置于签发与落库）。
     *
     * @param visitId 就诊号，非空
     * @param request 开单请求，非空
     * @return 申请单出参（status=CREATED），非空
     * @throws BizException OP-1001/OP-1011/OP-1017/OP-1019（语义见接口 javadoc）
     */
    @Override
    @Transactional
    public ClinicOrderVO create(String visitId, OrderCreateRequest request) {
        // ① visit 定位与终态守卫（红线 5：FINISHED/CANCELLED 后拒绝一切开单动作）
        Visit visit = visitMapper.selectOne(Wrappers.<Visit>lambdaQuery().eq(Visit::getVisitId, visitId));
        if (visit == null) {
            throw new BizException(
                    OutpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "就诊记录不存在：visitId=" + visitId);
        }
        if (visit.getStatus() == VisitStatus.FINISHED || visit.getStatus() == VisitStatus.CANCELLED) {
            log.warn(
                    "开单拒绝：visit 已终态（FINISHED/CANCELLED 后拒绝一切开单/缴费/执行动作）：visitId={}，status={}",
                    visitId,
                    visit.getStatus().getCode());
            throw new BizException(
                    OutpatientErrorCode.VISIT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "就诊已终态（" + visit.getStatus().getCode() + "），拒绝开单：visitId=" + visitId);
        }
        // ② 词表与格式显式校验（单据类型五类；quantity DECIMAL string——禁裸 parse 前置防线）
        if (!CREATABLE_ORDER_TYPES.contains(request.orderType())) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "单据类型词表外（EXAM/LAB/TREATMENT/DISPOSAL/MATERIAL）：" + request.orderType());
        }
        for (OrderItemRequest item : request.items()) {
            if (!DECIMAL_PATTERN.matcher(item.quantity()).matches()) {
                throw new BizException(
                        OutpatientErrorCode.PARAM_FORMAT_INVALID,
                        HttpStatus.BAD_REQUEST,
                        "数量格式违例（须为非负十进制串）：" + item.quantity());
            }
        }
        // ② 执业授权强校验：运行态 userId 直作 employeeId（Task 2 身份链口径），未过 OP-1017 403
        long employeeId = parseOperatorAsEmployeeId();
        PracticeCheckResult practice = practiceCheckPort.check(employeeId, PRACTICE_GRANT_PRESCRIPTION);
        if (!practice.passed()) {
            log.warn(
                    "开单拒绝：执业授权未过：visitId={}，employeeId={}，grantType={}，reason={}",
                    visitId,
                    employeeId,
                    PRACTICE_GRANT_PRESCRIPTION,
                    practice.reason());
            throw new BizException(
                    OutpatientErrorCode.PRACTICE_CHECK_FAILED, HttpStatus.FORBIDDEN, "开单执业授权未过：" + practice.reason());
        }
        // ③ order_no 签发（OP+yyyyMMdd+6 位流水）
        String orderNo = issueOrderNo();
        // ④ 主单+明细行落库（quantity DECIMAL string 红线——文本原样入列）
        ClinicOrder order = new ClinicOrder();
        order.setOrderNo(orderNo);
        order.setVisitId(visitId);
        order.setPatientId(visit.getPatientId());
        order.setOrderType(OrderType.fromCode(request.orderType()));
        order.setOrderDoctorId(OperatorContextHolder.get());
        order.setStatus(OrderStatus.CREATED);
        order.setCreatedBy(OperatorContextHolder.get());
        order.setUpdatedBy(OperatorContextHolder.get());
        // 数据库写操作：申请单主单落库（初始态 CREATED）
        clinicOrderMapper.insert(order);
        List<ClinicOrderItem> itemRows = request.items().stream()
                .map(item -> toItemRow(order.getId(), item, OperatorContextHolder.get()))
                .toList();
        // 数据库写操作：明细行批量落库（须在事务内——本方法 @Transactional 承载）
        clinicOrderItemMapper.insert(itemRows);
        // ⑤ 事务内发布 order.created（AFTER_COMMIT 出 MQ）：orderId=order_no，lines 逐行计费行
        events.publishEvent(new OutpatientDomainEvent(
                OutpatientMessagingConstants.EVENT_ORDER_CREATED,
                new OrderCreatedPayload(
                        orderNo,
                        visit.getPatientId(),
                        visitId,
                        itemRows.stream()
                                .map(row -> new OrderCreatedPayload.Line(row.getItemCode(), row.getQuantity()))
                                .toList())));
        log.info(
                "申请单开立完成：orderNo={}，visitId={}，orderType={}，orderDoctorId={}，lines={}",
                orderNo,
                visitId,
                request.orderType(),
                order.getOrderDoctorId(),
                itemRows.size());
        return toVO(order, itemRows);
    }

    /**
     * 申请单作废（接口 javadoc 契约）：RX_REF 引导性 409→CHARGED 拒绝引导退费链→状态机 CAS→
     * PENDING 费用行逐行作废（端口异常转译为可读业务错误码，作废原子回滚）。
     *
     * @param orderNo 申请单业务号，非空
     * @param reason  作废理由，非空白
     * @return 作废后申请单出参，非空
     * @throws BizException OP-1014/OP-1015（语义见接口 javadoc）
     */
    @Override
    @Transactional
    public ClinicOrderVO cancel(String orderNo, String reason) {
        // 数据库读操作：按单号定位申请单
        ClinicOrder order =
                clinicOrderMapper.selectOne(Wrappers.<ClinicOrder>lambdaQuery().eq(ClinicOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(
                    OutpatientErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND, "申请单不存在：orderNo=" + orderNo);
        }
        // RX_REF 行引导性拒绝（Spec :119 R2-10：作废必须经 M06 作废 API 发起，回流驱动随 Task 10）
        if (order.getOrderType() == OrderType.RX_REF) {
            throw new BizException(
                    OutpatientErrorCode.ORDER_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "处方引用行作废经 M06 作废链发起（POST /api/v1/pharmacy/prescriptions/{no}/cancel），"
                            + "pharmacy.prescription.cancelled 回流驱动本行 CANCELLED：orderNo=" + orderNo);
        }
        // CHARGED 拒绝作废并引导退费链（refund.approved 逆向随 Task 10）；其余非可作废态同口径拒绝
        if (order.getStatus() == OrderStatus.CHARGED) {
            throw new BizException(
                    OutpatientErrorCode.ORDER_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "已缴费单据不可作废，退费经 M13 退费链发起（退费审批回执驱动单据收敛）：orderNo=" + orderNo);
        }
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.PENDING_FEE) {
            throw new BizException(
                    OutpatientErrorCode.ORDER_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "申请单状态不允许作废（当前态 " + order.getStatus().getCode() + "）：orderNo=" + orderNo);
        }
        // 数据库写操作：状态机 CAS（CREATED/PENDING_FEE→CANCELLED）；0 行=并发已迁移，判 OP-1015
        if (clinicOrderMapper.casStatus(order.getId(), order.getStatus().getCode(), OrderStatus.CANCELLED.getCode())
                == 0) {
            log.warn(
                    "作废 CAS 落败（并发已迁移）：orderNo={}，当前态={}",
                    orderNo,
                    order.getStatus().getCode());
            throw new BizException(
                    OutpatientErrorCode.ORDER_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "申请单状态不允许作废（并发状态迁移）：orderNo=" + orderNo);
        }
        // 费用作废：M13 PENDING 行逐行作废（端口异常一律转译为可读业务错误码——W-20 关联面）
        int voidedFees = voidPendingFees(order, reason);
        order.setStatus(OrderStatus.CANCELLED);
        log.info(
                "申请单作废完成：orderNo={}，visitId={}，voidedFees={}，reason={}",
                orderNo,
                order.getVisitId(),
                voidedFees,
                reason);
        return toVO(order, itemsOf(order.getId()));
    }

    /**
     * 按就诊号查询申请单清单（id 降序；明细行单查批量 in 装配，禁 N+1）。
     *
     * @param visitId 就诊号，非空
     * @return 申请单出参清单（含明细行）；无单据返回空列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<ClinicOrderVO> listByVisit(String visitId) {
        // 数据库读操作：就诊号维度申请单清单（新单在前）
        List<ClinicOrder> orders = clinicOrderMapper.selectList(Wrappers.<ClinicOrder>lambdaQuery()
                .eq(ClinicOrder::getVisitId, visitId)
                .orderByDesc(ClinicOrder::getId));
        if (orders.isEmpty()) {
            return List.of();
        }
        // 数据库读操作：明细行批量装配（一次 in 查询按单分组，禁循环单查）
        Map<Long, List<ClinicOrderItem>> itemsByOrder = clinicOrderItemMapper
                .selectList(Wrappers.<ClinicOrderItem>lambdaQuery()
                        .in(
                                ClinicOrderItem::getOrderId,
                                orders.stream().map(ClinicOrder::getId).toList()))
                .stream()
                .collect(Collectors.groupingBy(ClinicOrderItem::getOrderId));
        return orders.stream()
                .map(order -> toVO(order, itemsByOrder.getOrDefault(order.getId(), List.of())))
                .toList();
    }

    /**
     * 缴费回执推进（接口 javadoc 契约）：单据 CAS CREATED→PENDING_FEE（0 行重读定性跳过）+
     * visit IN_CONSULT→PENDING_FEE 状态机迁移+每迁必记。
     *
     * @param orderNo 申请单业务号，非空
     * @throws IllegalStateException 申请单缺失（数据异常，死信留痕）时触发
     */
    @Override
    @Transactional
    public void markPendingFee(String orderNo) {
        // 数据库读操作：按单号定位申请单（回执单据缺失属数据异常，显式抛出死信留痕禁静默丢弃）
        ClinicOrder order =
                clinicOrderMapper.selectOne(Wrappers.<ClinicOrder>lambdaQuery().eq(ClinicOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new IllegalStateException("缴费回执推进失败：申请单缺失（数据异常，人工对账）：orderNo=" + orderNo);
        }
        // 数据库写操作：单据 CAS CREATED→PENDING_FEE；0 行=重投幂等/竞态，重读定性
        if (clinicOrderMapper.casStatus(order.getId(), OrderStatus.CREATED.getCode(), OrderStatus.PENDING_FEE.getCode())
                == 0) {
            ClinicOrder latest = clinicOrderMapper.selectById(order.getId());
            OrderStatus current = latest == null ? null : latest.getStatus();
            if (current == OrderStatus.PENDING_FEE) {
                // 重复投递幂等达成：单据已待缴费，AUTO 确认跳过
                log.info("缴费回执幂等跳过（单据已待缴费）：orderNo={}", orderNo);
                return;
            }
            log.warn("缴费回执跳过（单据当前态 {} 不接受待缴费推进，费用↔单据对账兜底）：orderNo={}", current, orderNo);
            return;
        }
        advanceVisitToPendingFee(order);
        log.info("缴费回执处理完成（单据转待缴费）：orderNo={}，visitId={}", orderNo, order.getVisitId());
    }

    // ---------------------------------------------------------------- 私有辅助

    /**
     * visit 待缴费推进（仅 IN_CONSULT 迁 PENDING_FEE，红线 5 状态机单点+每迁必记）：多单并推的
     * 已待缴费与诊毕竞态为正常态，非 IN_CONSULT 跳过不报错；CAS 并发落败 warn 跳过（回执单据侧
     * 已推进成功，visit 侧由后续回执/对账收敛）。
     *
     * @param order 已推进至 PENDING_FEE 的申请单，非空
     */
    private void advanceVisitToPendingFee(ClinicOrder order) {
        Visit visit = visitMapper.selectOne(Wrappers.<Visit>lambdaQuery().eq(Visit::getVisitId, order.getVisitId()));
        if (visit == null || visit.getStatus() != VisitStatus.IN_CONSULT) {
            log.info(
                    "visit 不处于就诊中，跳过待缴费推进：visitId={}，status={}",
                    order.getVisitId(),
                    visit == null ? null : visit.getStatus().getCode());
            return;
        }
        OutpatientVisitStateMachine.require(VisitStatus.IN_CONSULT.getCode(), VisitStatus.PENDING_FEE.getCode());
        // 数据库写操作：visit CAS IN_CONSULT→PENDING_FEE；命中后每迁必记（红线 5）
        if (visitMapper.casStatus(visit.getId(), VisitStatus.IN_CONSULT.getCode(), VisitStatus.PENDING_FEE.getCode())
                == 1) {
            insertVisitStatusLog(visit.getVisitId(), "缴费生成待缴费推进");
        } else {
            log.warn("visit 待缴费推进并发落败跳过：visitId={}", visit.getVisitId());
        }
    }

    /**
     * 作废单据关联的 PENDING 费用行（M13 权威面）：feesByVisit 定位 sourceRef=orderNo 且 PENDING
     * 的行逐行 cancelPendingFee。端口异常转译口径：billing 业务拒绝（BizException，BILL-xxxx 可读
     * 可定位）原样透传；底层异常（断连/数据访问等）转译为 OP-1015+中文可读 message 并回滚作废
     * （禁裸抛底层异常，W-20 关联面红线）。
     *
     * @param order 已 CAS 至 CANCELLED 的申请单，非空
     * @param reason 作废理由，非空白
     * @return 实际作废的费用行数（审计日志锚点）
     * @throws BizException billing 业务拒绝原样透传；底层异常转译 OP-1015（作废事务原子回滚）
     */
    private int voidPendingFees(ClinicOrder order, String reason) {
        try {
            List<VisitFeeView> fees = billingPort.feesByVisit(order.getVisitId());
            int voided = 0;
            for (VisitFeeView fee : fees) {
                // 仅作废本单据（sourceRef=orderNo）的 PENDING 行——其余单据/已缴费行不触碰
                if (FEE_STATUS_PENDING.equals(fee.status())
                        && order.getOrderNo().equals(fee.sourceRef())) {
                    billingPort.cancelPendingFee(fee.feeId(), reason);
                    voided++;
                }
            }
            return voided;
        } catch (BizException e) {
            // billing 业务拒绝原样透传（BILL-xxxx 业务错误码可读可定位，禁二次包装丢失语义）
            throw e;
        } catch (RuntimeException e) {
            log.error("费用作废端口调用异常，作废事务回滚：orderNo={}", order.getOrderNo(), e);
            throw new BizException(
                    OutpatientErrorCode.ORDER_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "费用作废失败（M13 端口异常），单据作废已回滚：orderNo=" + order.getOrderNo());
        }
    }

    /**
     * 操作者标识解析为 employeeId（运行态 userId 直作 employeeId，Task 2 身份链口径）：非数字串
     * 显式 OP-1019 拒绝（W-22⑦ 禁裸 parse——NumberFormatException 裸抛即底层异常）。
     *
     * @return 员工 ID（OperatorContextHolder 运行态 userId）
     * @throws BizException OP-1019（操作者标识非数字，无法作执业授权校验主体）时触发
     */
    private static long parseOperatorAsEmployeeId() {
        String operator = OperatorContextHolder.get();
        if (operator == null || !operator.matches("\\d+")) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "操作者标识非数字（无法定位执业授权主体）：" + operator);
        }
        return Long.parseLong(operator);
    }

    /**
     * 签发申请单号（OP+yyyyMMdd+6 位流水）：Redis INCR 当日键 fy:outpatient:order-seq:{yyyyMMdd}，
     * 首签（INCR 返回 1）续期 TTL 48h（裁决 11 同源，禁无过期键）；VisitIdIssuerImpl 同型三行直写
     * （禁为两用新建抽象）。签发自检：超 6 位上限 fail-fast（违例值禁落库）。
     *
     * @return 申请单业务号（16 位），非空
     * @throws IllegalStateException Redis 流水返回空或超 6 位上限时触发；建议处理策略：立即告警
     *                               人工介入，禁止违例值落库
     */
    private String issueOrderNo() {
        String today = LocalDate.now().format(SEQ_DATE);
        String seqKey = ORDER_SEQ_KEY_PREFIX + today;
        // 缓存写操作：Redis INCR 取当日流水（原子计数，跨实例并发安全）
        Long seq = redisTemplate.opsForValue().increment(seqKey);
        if (seq == null) {
            throw new IllegalStateException("申请单号签发失败：Redis 流水返回空，seqKey=" + seqKey);
        }
        if (seq == 1L) {
            // 首签续期 48h TTL（禁无过期键；后续签发不重复设置，保持 TTL 单次语义）
            redisTemplate.expire(seqKey, ORDER_SEQ_KEY_TTL);
        }
        if (seq > DAILY_SEQ_CAP) {
            throw new IllegalStateException("申请单号签发失败：当日流水超 6 位上限（seq=" + seq + "），seqKey=" + seqKey);
        }
        return "OP" + today + String.format("%0" + SEQ_WIDTH + "d", seq);
    }

    /**
     * 明细入参 → 明细行实体投影（quantity DECIMAL string 原样承载，禁数值化）。
     *
     * @param orderId  申请单主键，非空；来源：主单落库回填
     * @param item     明细入参，非空
     * @param operator 操作者，非空；审计列留痕
     * @return 明细行实体，非空
     */
    private static ClinicOrderItem toItemRow(Long orderId, OrderItemRequest item, String operator) {
        ClinicOrderItem row = new ClinicOrderItem();
        row.setOrderId(orderId);
        row.setItemCode(item.itemCode());
        row.setQuantity(item.quantity());
        row.setUsageSummary(item.usageSummary());
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        return row;
    }

    /**
     * 迁移留痕（红线 5 每迁必记：IN_CONSULT→PENDING_FEE，缴费回执推进专用）。
     *
     * @param visitId 就诊号，非空
     * @param reason  迁移原因，非空
     */
    private void insertVisitStatusLog(String visitId, String reason) {
        VisitStatusLog statusLog = new VisitStatusLog();
        statusLog.setVisitId(visitId);
        statusLog.setFromStatus(VisitStatus.IN_CONSULT);
        statusLog.setToStatus(VisitStatus.PENDING_FEE);
        statusLog.setReason(reason);
        statusLog.setOperator(OperatorContextHolder.get());
        // 数据库写操作：迁移日志每迁必记（红线 5）
        visitStatusLogMapper.insert(statusLog);
    }

    /**
     * 按单取明细行（作废出参组装）。
     *
     * @param orderId 申请单主键，非空
     * @return 明细行清单；无行返回空列表
     */
    private List<ClinicOrderItem> itemsOf(Long orderId) {
        return clinicOrderItemMapper.selectList(
                Wrappers.<ClinicOrderItem>lambdaQuery().eq(ClinicOrderItem::getOrderId, orderId));
    }

    /**
     * 实体+明细 → 申请单出参投影（quantity DECIMAL string 透传，禁数值化——D-18 同源）。
     *
     * @param order 申请单实体，非空
     * @param items 明细行清单，非空
     * @return 申请单出参，非空
     */
    private static ClinicOrderVO toVO(ClinicOrder order, List<ClinicOrderItem> items) {
        return new ClinicOrderVO(
                order.getId(),
                order.getOrderNo(),
                order.getVisitId(),
                order.getPatientId(),
                order.getOrderType(),
                order.getExtRef(),
                order.getOrderDoctorId(),
                order.getValidTo(),
                order.getStatus(),
                order.getFeeSettlementId(),
                items.stream()
                        .map(row -> new ClinicOrderVO.Item(row.getItemCode(), row.getQuantity(), row.getUsageSummary()))
                        .toList());
    }
}
