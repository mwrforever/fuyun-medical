package com.fuyun.outpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.billing.api.OutpatientBillingPort;
import com.fuyun.billing.api.RefundApprovedPayload;
import com.fuyun.billing.api.VisitFeeView;
import com.fuyun.billing.api.VisitRefundCommand;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.AppointmentBookedPayload;
import com.fuyun.outpatient.api.AppointmentCancelledPayload;
import com.fuyun.outpatient.api.AppointmentRescheduledPayload;
import com.fuyun.outpatient.api.AppointmentTimeoutPayload;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.api.VisitCancelledPayload;
import com.fuyun.outpatient.api.VisitRegisteredPayload;
import com.fuyun.outpatient.cache.PoolRedisGate;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.dto.AppointmentCreateRequest;
import com.fuyun.outpatient.dto.RescheduleRequest;
import com.fuyun.outpatient.entity.Appointment;
import com.fuyun.outpatient.entity.ApptCreditRecord;
import com.fuyun.outpatient.entity.ApptNumberPool;
import com.fuyun.outpatient.entity.Schedule;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.entity.VisitStatusLog;
import com.fuyun.outpatient.enums.ApptChannel;
import com.fuyun.outpatient.enums.ApptStatus;
import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.FeeStatusType;
import com.fuyun.outpatient.enums.PoolStatus;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.enums.VisitType;
import com.fuyun.outpatient.internal.DelayEnvelopeSender;
import com.fuyun.outpatient.internal.OutpatientDomainEvent;
import com.fuyun.outpatient.mapper.AppointmentMapper;
import com.fuyun.outpatient.mapper.ApptCreditRecordMapper;
import com.fuyun.outpatient.mapper.ApptNumberPoolMapper;
import com.fuyun.outpatient.mapper.ScheduleMapper;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.outpatient.mapper.VisitStatusLogMapper;
import com.fuyun.outpatient.properties.OutpatientProperties;
import com.fuyun.outpatient.service.IAppointmentService;
import com.fuyun.outpatient.service.IVisitIdIssuer;
import com.fuyun.outpatient.vo.AppointmentVO;
import com.fuyun.outpatient.vo.ApptCreditVO;
import com.fuyun.outpatient.vo.VisitVO;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预约/当日挂号服务实现（M03 FU-M03-02/03，Task 5 写路径唯一入口；Task 6 扩退号退费联动与改期）：
 * 统一预约主流程七步——①患者归一+冻结拦截（PatientContextResolver，业务以 resolvedPatientId 关联）
 * →②爽约限约拦截（信用窗口 NO_SHOW 计数达阈值且限约区间覆盖今日）→③限购拦截（同日同科
 * RESERVED/TAKEN 命中即拒，uk_appt_patient 为最终防线）→④池行复核（ACTIVE+余量谓词，停诊 OP-1004）
 * →⑤Redis 预扣（双道闸第一道：Lua 余量不足 -1 判 OP-1003 直接拒绝不降级——余量谓词旁路即超卖面；
 * 仅键缺失/Redis 异常走 DB 条件更新降级，warn 留痕）→⑥appointment 落库+池行 casOccupy（同事务
 * 双道闸第二道，0 行重读重试 ≤2 次后判 OP-1003 并回补 Redis 持有）→⑦渠道分流：PORTAL 写
 * pay_deadline 占位+延迟信封入队（事务内直发例外，见 DelayEnvelopeSender 裁决链）+发布
 * appointment.booked；WINDOW/KIOSK 一步直达 TAKEN（同事务签发 visit+casTake+发布 visit.registered）。
 * Task 6：退号四分支（未支付免退费直取消/已付退费申请待回执/已取号退费待回执/已报到拒线上退，
 * 终态一律 billing.refund.approved 回执后置——资金无涉红线裁决 7）、改期先占新后退旧（reschedule_of
 * 链）与爽约信用管理面。事件发布走事务内 publishEvent → AFTER_COMMIT 出 MQ（A.4.2-7）。
 * 资金无涉红线（裁决 7）：本类零金额逻辑。线程安全：无状态单例。装配归 OutpatientWebConfig
 *
 * @Import；com.fuyun.outpatient.service.impl 包 = JaCoCo PACKAGE LINE 1.00 覆盖对象。
 */
@Slf4j
public class AppointmentServiceImpl implements IAppointmentService {

    /** P1 开放预约渠道面（窗口/自助=当日挂号一步 TAKEN；portal=支付时限占位），其余词表位拒绝 */
    private static final Set<ApptChannel> P1_BOOK_CHANNELS =
            Set.of(ApptChannel.WINDOW, ApptChannel.KIOSK, ApptChannel.PORTAL);

    /** 线上渠道面（P1 唯一线上预约渠道 PORTAL；退号时限 OP-1010 判定域——窗口/自助渠道不受限） */
    private static final Set<ApptChannel> ONLINE_CHANNELS = Set.of(ApptChannel.PORTAL);

    /** billing 可退费用行状态词表（与 RefundServiceImpl.apply 行态守卫同源：SETTLED/PART_REFUND） */
    private static final Set<String> REFUNDABLE_FEE_STATUSES = Set.of("SETTLED", "PART_REFUND");

    /** 挂号费整号退数量（DECIMAL string，D-18 同源；P1 挂号费单次计费 quantity=1 口径） */
    private static final String REGISTRATION_REFUND_QUANTITY = "1";

    /** portal 匿名链路操作者哨兵值（裁决 13：不经 OperatorContextHolder，留痕取 PORTAL） */
    private static final String PORTAL_SENTINEL = "PORTAL";

    /** 系统定性动作操作者（超时释放/信用记录写入） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 池键 TTL 锚点时刻：排班日次日 02:00（Redis↔池行每日对账窗口缓冲，A.5-1，与 ScheduleServiceImpl 同锚） */
    private static final LocalTime POOL_KEY_TTL_ANCHOR = LocalTime.of(2, 0);

    /** 支付占位键前缀：fy:outpatient:pay-hold:{apptNo}（A.5-1；{apptNo} 兼作 Redis Cluster hash tag） */
    private static final String PAY_HOLD_KEY_PREFIX = "fy:outpatient:pay-hold:";

    /** 支付占位键缓冲：支付时限 + 5min（A.5-1 键规范，覆盖延迟档位到期对账余量） */
    private static final Duration PAY_HOLD_BUFFER = Duration.ofMinutes(5);

    /** 预约单号流水键前缀：fy:outpatient:appt-seq:{yyyyMMdd}（visit-seq 同型，A.5-1） */
    private static final String APPT_SEQ_KEY_PREFIX = "fy:outpatient:appt-seq:";

    /** 预约单号流水键 TTL（48h，裁决 11 同源，禁无过期键） */
    private static final Duration APPT_SEQ_KEY_TTL = Duration.ofHours(48);

    /** 签发日期段格式（yyyyMMdd） */
    private static final DateTimeFormatter SEQ_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** Redis 预扣返回哨兵：余量不足（判 OP-1003 直接拒绝，不降级——Task 4 对齐裁定①） */
    private static final int REDIS_DEDUCT_EXHAUSTED = -1;

    /** casOccupy 重读重试上限（首次 + 2 次重试，Global Constraints 双道闸口径） */
    private static final int CAS_RETRY_TIMES = 2;

    private final PatientContextResolver patientContextResolver;

    private final IVisitIdIssuer visitIdIssuer;

    private final ApptNumberPoolMapper apptNumberPoolMapper;

    private final ScheduleMapper scheduleMapper;

    private final AppointmentMapper appointmentMapper;

    private final VisitMapper visitMapper;

    private final VisitStatusLogMapper visitStatusLogMapper;

    private final ApptCreditRecordMapper apptCreditRecordMapper;

    private final PoolRedisGate poolRedisGate;

    private final StringRedisTemplate redisTemplate;

    private final DelayEnvelopeSender delayEnvelopeSender;

    private final OutpatientBillingPort billingPort;

    private final ApplicationEventPublisher events;

    private final OutpatientProperties properties;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import，backend 宪法 B.1）。
     *
     * @param patientContextResolver 患者上下文解析（patient api 契约），非空；归一主档+冻结拦截
     * @param visitIdIssuer          就诊号签发器，非空；CF-3 O 型 14 位签发
     * @param apptNumberPoolMapper   号源池行 mapper，非空；复核/占用 CAS/回补 CAS
     * @param scheduleMapper         排班日历 mapper，非空；dept_code/session/doctor_id 关联读
     * @param appointmentMapper      预约单 mapper，非空；落库/状态 CAS/取号 CAS/费态回写 CAS
     * @param visitMapper            就诊记录 mapper，非空；挂号落库/就诊号定位/退号回滚 CAS
     * @param visitStatusLogMapper   就诊状态迁移日志 mapper，非空；红线 5 每迁必记（退号回执回滚）
     * @param apptCreditRecordMapper 爽约信用 mapper，非空；窗口计数/信用行落库/手工解除
     * @param poolRedisGate          号源 Redis 预扣闸，非空；双道闸第一道
     * @param redisTemplate          Redis 字符串模板，非空；预约单号流水键与支付占位键
     * @param delayEnvelopeSender    延迟信封发送器，非空；支付时限占位登记
     * @param billingPort            billing 对接端口（billing api 契约），非空；退号退费统一免审档与
     *                               费用行定位（裁决 7 进程内承载，禁 HTTP 自调用）
     * @param events                 Spring 应用事件发布器，非空；事务内发布 AFTER_COMMIT 出 MQ
     * @param properties             门诊域参数，非空；支付时限/线上退号时限/爽约窗口/阈值/限约天数
     */
    public AppointmentServiceImpl(
            PatientContextResolver patientContextResolver,
            IVisitIdIssuer visitIdIssuer,
            ApptNumberPoolMapper apptNumberPoolMapper,
            ScheduleMapper scheduleMapper,
            AppointmentMapper appointmentMapper,
            VisitMapper visitMapper,
            VisitStatusLogMapper visitStatusLogMapper,
            ApptCreditRecordMapper apptCreditRecordMapper,
            PoolRedisGate poolRedisGate,
            StringRedisTemplate redisTemplate,
            DelayEnvelopeSender delayEnvelopeSender,
            OutpatientBillingPort billingPort,
            ApplicationEventPublisher events,
            OutpatientProperties properties) {
        this.patientContextResolver = patientContextResolver;
        this.visitIdIssuer = visitIdIssuer;
        this.apptNumberPoolMapper = apptNumberPoolMapper;
        this.scheduleMapper = scheduleMapper;
        this.appointmentMapper = appointmentMapper;
        this.visitMapper = visitMapper;
        this.visitStatusLogMapper = visitStatusLogMapper;
        this.apptCreditRecordMapper = apptCreditRecordMapper;
        this.poolRedisGate = poolRedisGate;
        this.redisTemplate = redisTemplate;
        this.delayEnvelopeSender = delayEnvelopeSender;
        this.billingPort = billingPort;
        this.events = events;
        this.properties = properties;
    }

    /**
     * 统一预约/当日挂号（主流程七步锁死，步骤注释即执行序）。
     *
     * @param request 预约请求，非空；来源：多渠道统一入口（portal 经介质解析换 patientId 后进入）
     * @return 预约单出参，非空；窗口/自助直达 TAKEN 携 visit_id
     * @throws BizException OP-1002/OP-1003/OP-1004/OP-1005/OP-1006/OP-1007/OP-1019（语义见接口 javadoc）
     */
    @Override
    @Transactional
    public AppointmentVO book(AppointmentCreateRequest request) {
        // ① 渠道解析与 P1 开放面校验：词表外或预留位渠道显式 400（禁裸 parse，W-22⑦ 口径）
        ApptChannel channel;
        try {
            channel = ApptChannel.fromCode(request.channel());
        } catch (IllegalArgumentException e) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "预约渠道词表外：" + request.channel());
        }
        if (!P1_BOOK_CHANNELS.contains(channel)) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "渠道 P1 未开放预约入口：" + channel.getCode());
        }
        // ① 患者归一+冻结拦截：从档解析收敛主档（M02 红线 1），冻结档案拒绝新就诊（OP-1007）
        PatientContextView context = patientContextResolver.resolve(request.patientId());
        if (context.blocked()) {
            log.warn(
                    "挂号拦截：患者冻结中，入参 patientId={}，resolvedPatientId={}，原因={}",
                    request.patientId(),
                    context.resolvedPatientId(),
                    context.blockReason());
            throw new BizException(
                    OutpatientErrorCode.PATIENT_BLOCKED, HttpStatus.CONFLICT, "患者冻结中禁止挂号：" + context.blockReason());
        }
        long patientId = context.resolvedPatientId();
        // 限约/限购判定与单据冗余列依赖：池行与排班先行读（业务校验序不变）
        ApptNumberPool pool = apptNumberPoolMapper.selectById(request.poolId());
        if (pool == null) {
            throw new BizException(
                    OutpatientErrorCode.POOL_NOT_FOUND, HttpStatus.NOT_FOUND, "号源池不存在：poolId=" + request.poolId());
        }
        Schedule schedule = scheduleMapper.selectById(pool.getScheduleId());
        if (schedule == null) {
            throw new BizException(
                    OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "排班不存在或已删除：scheduleId=" + pool.getScheduleId());
        }
        // ② 爽约限约拦截：窗口内 NO_SHOW 计数达阈值 且 限约区间覆盖今日（restrict_to ≥ 今日）→ OP-1006
        List<ApptCreditRecord> credits = apptCreditRecordMapper.selectList(Wrappers.<ApptCreditRecord>lambdaQuery()
                .eq(ApptCreditRecord::getPatientId, patientId)
                .eq(ApptCreditRecord::getAction, ApptCreditRecord.ACTION_NO_SHOW)
                .ge(ApptCreditRecord::getOccurredAt, OffsetDateTime.now().minusDays(properties.noShowWindowDays())));
        boolean overThreshold = credits.size() >= properties.noShowThreshold();
        boolean restrictActive = credits.stream()
                .anyMatch(c -> c.getRestrictTo() != null && !c.getRestrictTo().isBefore(LocalDate.now()));
        if (overThreshold && restrictActive) {
            log.warn(
                    "挂号拦截：爽约限约期内，patientId={}，窗口内 NO_SHOW 计数={}，阈值={}",
                    patientId,
                    credits.size(),
                    properties.noShowThreshold());
            throw new BizException(OutpatientErrorCode.APPT_RESTRICTED, HttpStatus.CONFLICT, "爽约限约期内暂不可预约（窗口内爽约达阈值）");
        }
        // ③ 限购拦截：同患者同日同科已有 RESERVED/TAKEN 有效单 → OP-1005（uk_appt_patient 唯一索引为最终防线）
        Long duplicates = appointmentMapper.selectCount(Wrappers.<Appointment>lambdaQuery()
                .eq(Appointment::getPatientId, patientId)
                .eq(Appointment::getSchedDate, schedule.getSchedDate())
                .eq(Appointment::getDeptCode, schedule.getDeptCode())
                .in(Appointment::getStatus, ApptStatus.RESERVED, ApptStatus.TAKEN));
        if (duplicates != null && duplicates > 0) {
            log.warn(
                    "挂号拦截：同日同科限购，patientId={}，schedDate={}，deptCode={}",
                    patientId,
                    schedule.getSchedDate(),
                    schedule.getDeptCode());
            throw new BizException(
                    OutpatientErrorCode.DUPLICATE_APPOINTMENT,
                    HttpStatus.CONFLICT,
                    "同日同科限约（同一就诊日同一科室仅可预约一次）：deptCode=" + schedule.getDeptCode());
        }
        // ④ 池行复核：非 ACTIVE（停诊联动）判 OP-1004；余量谓词旁路判 OP-1003
        if (pool.getStatus() != PoolStatus.ACTIVE) {
            throw new BizException(
                    OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "号源已停用（停诊联动），不可预约：poolId=" + pool.getId());
        }
        if (pool.getUsedCount() >= pool.getTotalQuota()) {
            throw new BizException(
                    OutpatientErrorCode.POOL_EXHAUSTED, HttpStatus.CONFLICT, "号源不足：poolId=" + pool.getId());
        }
        // ⑤ Redis 预扣（双道闸第一道）：余量不足（-1）判 OP-1003 直接拒绝不降级（余量谓词旁路即超卖面，
        // Task 4 对齐裁定①）；仅键缺失（-2）或 Redis 异常（DataAccessException）降级直连 DB 条件更新，warn 留痕
        boolean redisHeld = false;
        try {
            int remain = poolRedisGate.deduct(pool.getId(), pool.getTotalQuota(), poolKeyTtl(schedule.getSchedDate()));
            if (remain == REDIS_DEDUCT_EXHAUSTED) {
                throw new BizException(
                        OutpatientErrorCode.POOL_EXHAUSTED, HttpStatus.CONFLICT, "号源不足：poolId=" + pool.getId());
            }
            if (remain >= 0) {
                redisHeld = true;
            } else {
                log.warn("Redis 池键缺失，降级直连 DB 条件更新：poolId={}，扣减返回={}", pool.getId(), remain);
            }
        } catch (DataAccessException e) {
            log.warn("Redis 预扣异常，降级直连 DB 条件更新（功能不中断）：poolId={}，原因={}", pool.getId(), e.getMessage());
        }
        // ⑥ appointment 落库 + 池行条件更新（双道闸第二道，同事务；0 行重读重试 ≤2 次后判 OP-1003）
        String operator = operatorOf(channel);
        Appointment appointment = new Appointment();
        appointment.setApptNo(issueApptNo());
        appointment.setPatientId(patientId);
        appointment.setScheduleId(schedule.getId());
        appointment.setPoolId(pool.getId());
        appointment.setDeptCode(schedule.getDeptCode());
        appointment.setApptType(pool.getApptType());
        appointment.setSchedDate(schedule.getSchedDate());
        appointment.setSlotStart(pool.getSlotStart());
        appointment.setSlotEnd(pool.getSlotEnd());
        appointment.setChannel(channel);
        appointment.setFeeStatus(FeeStatusType.UNPAID);
        // PORTAL 渠道写支付时限占位（WINDOW/KIOSK 无支付时限语义，casTake 守卫谓词对空值放行）
        appointment.setPayDeadline(
                channel == ApptChannel.PORTAL ? OffsetDateTime.now().plus(properties.appointmentTimeout()) : null);
        appointment.setStatus(ApptStatus.RESERVED);
        appointment.setCreatedBy(operator);
        appointment.setUpdatedBy(operator);
        try {
            // 数据库写操作：预约单落库；uk 命中=并发限购抢落（预查后竞态窗口），按 OP-1005 整单回滚
            appointmentMapper.insert(appointment);
        } catch (DuplicateKeyException e) {
            // 事务回滚前回补 Redis 持有（防败者侧第一道闸余量 -1 不恢复致快路径不可售，与 CAS 耗尽路径对称）
            releaseRedisHoldQuietly(pool, schedule, redisHeld);
            log.warn(
                    "预约并发限购冲突回滚：patientId={}，schedDate={}，deptCode={}",
                    patientId,
                    schedule.getSchedDate(),
                    schedule.getDeptCode());
            throw new BizException(
                    OutpatientErrorCode.DUPLICATE_APPOINTMENT,
                    HttpStatus.CONFLICT,
                    "同日同科限约（并发冲突请重试）：deptCode=" + schedule.getDeptCode());
        }
        occupyPoolCasWithRetry(pool, schedule, redisHeld);
        // ⑦ 渠道分流：PORTAL 占位登记；WINDOW/KIOSK 一步直达 TAKEN（同事务签发 visit）
        if (channel == ApptChannel.PORTAL) {
            holdPortalSlot(appointment, pool, schedule, redisHeld);
            return toAppointmentVO(appointment);
        }
        return registerVisitAndTake(appointment, pool, schedule, operator);
    }

    /**
     * 预约取号（RESERVED→TAKEN，同事务签发 visit 并删支付占位键；TAKEN 幂等返回）。
     *
     * @param apptNo 预约单业务号，非空
     * @return 就诊记录出参（REGISTERED），非空
     * @throws BizException OP-1008（支付时限已过）/ OP-1009（预约单不存在或状态不允许取号）
     */
    @Override
    @Transactional
    public VisitVO take(String apptNo) {
        Appointment appointment =
                appointmentMapper.selectOne(Wrappers.<Appointment>lambdaQuery().eq(Appointment::getApptNo, apptNo));
        if (appointment == null) {
            throw new BizException(
                    OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "预约单不存在：apptNo=" + apptNo);
        }
        if (appointment.getStatus() == ApptStatus.TAKEN) {
            // TAKEN 幂等返回：重复取号/重复扫码返回既有 visit（禁止重复签发，uk_visit_id 兜底）
            Visit existing = visitMapper.selectOne(
                    Wrappers.<Visit>lambdaQuery().eq(Visit::getVisitId, appointment.getVisitId()));
            if (existing == null) {
                throw new BizException(
                        OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "预约单已取号但就诊记录缺失（数据异常）：apptNo=" + apptNo);
            }
            log.info("预约取号幂等返回（已 TAKEN）：apptNo={}，visitId={}", apptNo, existing.getVisitId());
            return toVisitVO(existing);
        }
        // 签发在前（casTake 同步回填 visit_id）；CAS 落败时流水号跳号，业务无副作用
        String visitId = visitIdIssuer.issue();
        int taken = appointmentMapper.casTake(appointment.getId(), visitId);
        if (taken == 0) {
            Appointment current = appointmentMapper.selectById(appointment.getId());
            ApptStatus status = current == null ? null : current.getStatus();
            if (status == ApptStatus.RESERVED) {
                // RESERVED 且 CAS 落败唯一成因为支付时限守卫谓词（pay_deadline > now() 不再成立）
                log.warn("预约取号拒绝：支付时限已过，apptNo={}，payDeadline={}", apptNo, appointment.getPayDeadline());
                throw new BizException(
                        OutpatientErrorCode.PAY_DEADLINE_PASSED,
                        HttpStatus.CONFLICT,
                        "支付时限已过，预约占位已失效：apptNo=" + apptNo);
            }
            throw new BizException(
                    OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "预约单状态不允许取号（当前态 " + (status == null ? "UNKNOWN" : status.getCode()) + "）：apptNo=" + apptNo);
        }
        ApptNumberPool pool = apptNumberPoolMapper.selectById(appointment.getPoolId());
        Schedule schedule = pool == null ? null : scheduleMapper.selectById(pool.getScheduleId());
        if (pool == null || schedule == null) {
            // 取号 CAS 已成功但关联行缺失属数据异常（uk 外键语义由业务维护），fail-fast 回滚整单
            throw new IllegalStateException("预约取号失败：号源池/排班定位失败，apptNo=" + apptNo);
        }
        Visit visit = buildVisit(appointment, visitId, pool, schedule, OperatorContextHolder.get());
        visitMapper.insert(visit);
        // 缓存操作：删除支付占位键（删除失败不阻断取号，TTL 兜底自然过期）
        deletePayHoldQuietly(apptNo);
        publishVisitRegistered(visit, schedule);
        log.info(
                "预约取号完成：apptNo={}，visitId={}，patientId={}，deptCode={}",
                apptNo,
                visitId,
                appointment.getPatientId(),
                appointment.getDeptCode());
        return toVisitVO(visit);
    }

    /**
     * 支付超时释放（延迟队列消费业务面，双加强：业务态幂等守卫 + version 条件回池）。
     *
     * @param payload 超时回调载荷（apptNo/patientId/poolId），非空
     * @throws IllegalStateException 预约单按 appt_no 定位失败（自产自消链路的数据异常，交死信留痕）
     */
    @Override
    @Transactional
    public void markTimeout(AppointmentTimeoutPayload payload) {
        Appointment appointment = appointmentMapper.selectOne(
                Wrappers.<Appointment>lambdaQuery().eq(Appointment::getApptNo, payload.apptNo()));
        if (appointment == null) {
            throw new IllegalStateException("预约支付超时释放失败：预约单不存在，apptNo=" + payload.apptNo());
        }
        // 业务态校验幂等守卫：RESERVED→NO_SHOW 影响 1 行才执行释放面——0 行=已取号 TAKEN/已取消 CANCELLED/
        // 已释放 NO_SHOW（含重复超时消息），info 幂等 ACK 跳过，禁二次回池/二次 credit 行
        int flipped = appointmentMapper.casStatus(
                appointment.getId(), ApptStatus.RESERVED.getCode(), ApptStatus.NO_SHOW.getCode());
        if (flipped == 0) {
            Appointment current = appointmentMapper.selectById(appointment.getId());
            log.info(
                    "预约支付超时幂等跳过（当前态 {}）：apptNo={}",
                    current == null ? "UNKNOWN" : current.getStatus().getCode(),
                    payload.apptNo());
            return;
        }
        // 池行回池（version 乐观锁条件更新，防并发双回补；Task 6 退号/回执路径共用同一释放面）
        releasePoolConditionally(payload.poolId(), payload.apptNo());
        deletePayHoldQuietly(payload.apptNo());
        recordNoShowCredit(payload.patientId());
        log.warn(
                "预约支付超时已置 NO_SHOW：apptNo={}，patientId={}，poolId={}",
                payload.apptNo(),
                payload.patientId(),
                payload.poolId());
    }

    // ---------------------------------------------------------------- 退号退费联动（Task 6）

    /**
     * 退号（四分支锁死，裁决 7——退费一律经 billing 端口免审档，appointment/visit 终态一律
     * billing.refund.approved 回执后置）：入口守卫（存在性/终态/线上退号时限）后按状态分流——
     * TAKEN 先核 visit 态（非 REGISTERED 即已报到/已接诊，OP-1010 拒线上退）再走退费链；
     * RESERVED 按费态分流（已付有结算锚走退费链保持占位待回执，否则免退费直取消+回池）。
     *
     * @param apptNo 预约单业务号，非空
     * @param reason 退号原因，非空白
     * @return 预约单出参（分支 1=CANCELLED；分支 2/3=原态占位待回执），非空
     * @throws BizException OP-1009（预约单不存在/终态不可退）/ OP-1010（线上时限外/已报到拒线上退）/
     *                      M13 退费守卫（BILL-*，端口原样透传）时触发
     */
    @Override
    @Transactional
    public AppointmentVO cancel(String apptNo, String reason) {
        // 数据库读操作：按业务号定位预约单
        Appointment appointment =
                appointmentMapper.selectOne(Wrappers.<Appointment>lambdaQuery().eq(Appointment::getApptNo, apptNo));
        if (appointment == null) {
            throw new BizException(
                    OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "预约单不存在：apptNo=" + apptNo);
        }
        ApptStatus status = appointment.getStatus();
        if (status != ApptStatus.RESERVED && status != ApptStatus.TAKEN) {
            throw new BizException(
                    OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "预约单状态不允许退号（当前态 " + status.getCode() + "）：apptNo=" + apptNo);
        }
        // 线上退号时限校验（窗口/自助渠道不受限——逾窗转窗口口径，Spec :137）：截止日=就诊日往前推
        // onlineCancelBeforeDays 天，当日（不足提前天数）线上退号关闭（北京/杭州调研依据 4 同款口径）
        if (ONLINE_CHANNELS.contains(appointment.getChannel())
                && appointment
                        .getSchedDate()
                        .minusDays(properties.onlineCancelBeforeDays())
                        .isBefore(LocalDate.now())) {
            log.warn(
                    "线上退号时限外拒绝：apptNo={}，schedDate={}，onlineCancelBeforeDays={}",
                    apptNo,
                    appointment.getSchedDate(),
                    properties.onlineCancelBeforeDays());
            throw new BizException(
                    OutpatientErrorCode.CANCEL_WINDOW_CLOSED, HttpStatus.CONFLICT, "线上退号时限已过，请到院窗口办理：apptNo=" + apptNo);
        }
        if (status == ApptStatus.TAKEN) {
            return cancelTaken(appointment, reason);
        }
        return cancelReserved(appointment, reason);
    }

    /**
     * 改期（退旧号新，reschedule_of 链；「先占新后退旧防两头空」Spec :137）：新池行全套预扣+CAS+
     * 新 appointment 行（RESERVED、reschedule_of=旧单号），成功后旧单 CAS→CANCELLED+回池+删占位键；
     * 任一步失败整体回滚（事务+Redis 持有/占位键对称回补），成功发布 appointment.rescheduled。
     * 改期仅承载未支付占位迁移：已支付单拒（防资金面绕过退费链静默作废，资金无涉红线裁决 7）。
     *
     * @param apptNo  旧预约单业务号，非空
     * @param request 改期请求（newPoolId），非空
     * @return 新预约单出参（RESERVED），非空
     * @throws BizException OP-1002/OP-1003/OP-1004/OP-1005/OP-1009（语义见接口 javadoc）时触发
     */
    @Override
    @Transactional
    public AppointmentVO reschedule(String apptNo, RescheduleRequest request) {
        // 数据库读操作：旧单定位与改期资格守卫（仅 RESERVED 未支付占位可改期）
        Appointment old =
                appointmentMapper.selectOne(Wrappers.<Appointment>lambdaQuery().eq(Appointment::getApptNo, apptNo));
        if (old == null) {
            throw new BizException(
                    OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "预约单不存在：apptNo=" + apptNo);
        }
        if (old.getStatus() != ApptStatus.RESERVED) {
            throw new BizException(
                    OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "仅未取号占位单可改期（当前态 " + old.getStatus().getCode() + "，TAKEN 走退号链）：apptNo=" + apptNo);
        }
        if (old.getFeeStatus() == FeeStatusType.PAID) {
            throw new BizException(
                    OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "已支付预约单不支持改期，请先退号退费后重新预约：apptNo=" + apptNo);
        }
        // 新池行复核（与预约主链 ④ 同口径）：ACTIVE+余量谓词，停诊 OP-1004/不足 OP-1003
        ApptNumberPool newPool = apptNumberPoolMapper.selectById(request.newPoolId());
        if (newPool == null) {
            throw new BizException(
                    OutpatientErrorCode.POOL_NOT_FOUND, HttpStatus.NOT_FOUND, "号源池不存在：poolId=" + request.newPoolId());
        }
        Schedule newSchedule = scheduleMapper.selectById(newPool.getScheduleId());
        if (newSchedule == null) {
            throw new BizException(
                    OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "排班不存在或已删除：scheduleId=" + newPool.getScheduleId());
        }
        if (newPool.getStatus() != PoolStatus.ACTIVE) {
            throw new BizException(
                    OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "号源已停用（停诊联动），不可改期：poolId=" + newPool.getId());
        }
        if (newPool.getUsedCount() >= newPool.getTotalQuota()) {
            throw new BizException(
                    OutpatientErrorCode.POOL_EXHAUSTED, HttpStatus.CONFLICT, "号源不足：poolId=" + newPool.getId());
        }
        // ① 新池 Redis 预扣（双道闸第一道，-1 拒绝不降级；键缺失/异常降级 DB CAS，warn 留痕）
        boolean redisHeld = false;
        try {
            int remain = poolRedisGate.deduct(
                    newPool.getId(), newPool.getTotalQuota(), poolKeyTtl(newSchedule.getSchedDate()));
            if (remain == REDIS_DEDUCT_EXHAUSTED) {
                throw new BizException(
                        OutpatientErrorCode.POOL_EXHAUSTED, HttpStatus.CONFLICT, "号源不足：poolId=" + newPool.getId());
            }
            if (remain >= 0) {
                redisHeld = true;
            } else {
                log.warn("Redis 池键缺失，降级直连 DB 条件更新：poolId={}，扣减返回={}", newPool.getId(), remain);
            }
        } catch (DataAccessException e) {
            log.warn("Redis 预扣异常，降级直连 DB 条件更新（功能不中断）：poolId={}，原因={}", newPool.getId(), e.getMessage());
        }
        // ② 新 appointment 行落库（reschedule_of 链锚定旧单号）——同日同科目标与既有有效单冲突由
        // uk_appt_patient 兜底，DuplicateKey 映射 OP-1005（先占新语义下同日同科改期须先退旧再约新）
        Appointment fresh = buildRescheduledAppointment(old, newPool, newSchedule);
        try {
            appointmentMapper.insert(fresh);
        } catch (DuplicateKeyException e) {
            releaseRedisHoldQuietly(newPool, newSchedule, redisHeld);
            log.warn("改期并发限购冲突回滚：apptNo={}，patientId={}，newPoolId={}", apptNo, old.getPatientId(), newPool.getId());
            throw new BizException(
                    OutpatientErrorCode.DUPLICATE_APPOINTMENT,
                    HttpStatus.CONFLICT,
                    "改期目标与既有预约限购冲突（同日同科），请先退号后重新预约：apptNo=" + apptNo);
        }
        // ③ 新池行 CAS 占用（双道闸第二道，重读重试 ≤2 次后判 OP-1003 并回补 Redis 持有）——旧单零写
        occupyPoolCasWithRetry(newPool, newSchedule, redisHeld);
        // ④ PORTAL 占位登记（占位键+延迟信封随新单号，占位时限重置为完整支付时限；失败对称回补整单回滚）
        if (old.getChannel() == ApptChannel.PORTAL) {
            registerRescheduledHold(fresh, newPool, newSchedule, redisHeld);
        }
        // ⑤ 后放旧：旧单 CAS→CANCELLED（0 行=并发已迁移，对称回补新面后 fail-fast 整单回滚）
        if (appointmentMapper.casStatus(old.getId(), ApptStatus.RESERVED.getCode(), ApptStatus.CANCELLED.getCode())
                == 0) {
            releaseRedisHoldQuietly(newPool, newSchedule, redisHeld);
            deletePayHoldQuietly(fresh.getApptNo());
            throw new IllegalStateException("改期失败：旧单并发状态迁移（CAS 落败）：apptNo=" + apptNo);
        }
        // ⑥ 旧池回池+旧占位键清理（version 谓词条件回池，与超时释放/退号取消共用释放面）
        releasePoolConditionally(old.getPoolId(), old.getApptNo());
        deletePayHoldQuietly(old.getApptNo());
        // ⑦ 发布改期链事件（事务内 publishEvent → AFTER_COMMIT 出 MQ）
        events.publishEvent(new OutpatientDomainEvent(
                OutpatientMessagingConstants.EVENT_APPOINTMENT_RESCHEDULED,
                new AppointmentRescheduledPayload(
                        old.getApptNo(),
                        fresh.getApptNo(),
                        old.getPatientId(),
                        newSchedule.getSchedDate().format(SEQ_DATE),
                        newPool.getSlotStart().toString())));
        log.info(
                "改期完成（先占新后退旧）：oldApptNo={}，newApptNo={}，patientId={}，newPoolId={}，newSchedDate={}",
                apptNo,
                fresh.getApptNo(),
                old.getPatientId(),
                newPool.getId(),
                newSchedule.getSchedDate());
        return toAppointmentVO(fresh);
    }

    /**
     * 退费回执消费业务（billing.refund.approved → 退号终态，appointment 分支）：按结算锚定位
     * RESERVED/TAKEN 单→状态 CAS 置 CANCELLED→费态 REFUNDED→回池→（TAKEN 态）visit 回滚→发布
     * cancelled（feeRefundTriggered=true）。无命中/并发落败幂等跳过（回执可重投，终态幂等收敛）。
     *
     * @param payload 退费回执载荷（V605 id 20 七组件），非空
     * @throws IllegalStateException 回执链路数据异常（TAKEN 单 visit 缺失，交死信留痕）
     */
    @Override
    @Transactional
    public void confirmRefundedCancel(RefundApprovedPayload payload) {
        // 数据库读操作：按结算锚定位退号链预约单（仅 RESERVED/TAKEN——已终态单零命中即幂等跳过）
        Appointment appointment = appointmentMapper.selectOne(Wrappers.<Appointment>lambdaQuery()
                .eq(Appointment::getFeeSettlementId, payload.settlementId())
                .in(Appointment::getStatus, ApptStatus.RESERVED, ApptStatus.TAKEN));
        if (appointment == null) {
            log.info(
                    "退费回执幂等跳过（无退号链预约单命中结算锚，非退号退费或已终态）：settlementId={}，refundNo={}",
                    payload.settlementId(),
                    payload.refundNo());
            return;
        }
        // 状态 CAS 幂等守卫：重复回执/并发已迁移 0 行即跳过（禁二次回池/二次终态事件）
        String from = appointment.getStatus().getCode();
        if (appointmentMapper.casStatus(appointment.getId(), from, ApptStatus.CANCELLED.getCode()) == 0) {
            log.info("退费回执幂等跳过（预约单状态 CAS 落败，并发已迁移）：apptNo={}，from={}", appointment.getApptNo(), from);
            return;
        }
        // 费态条件回写 PAID→REFUNDED（0 行=费态漂移，warn 留痕不阻断——退号取消与回池已先行完成）
        if (appointmentMapper.casMarkRefunded(appointment.getId()) == 0) {
            log.warn("退费回执费态回写落败（非 PAID 漂移）：apptNo={}，refundNo={}", appointment.getApptNo(), payload.refundNo());
        }
        // 号源回池+占位键清理（version 谓词条件回池，防并发双回补）
        releasePoolConditionally(appointment.getPoolId(), appointment.getApptNo());
        deletePayHoldQuietly(appointment.getApptNo());
        String reason = "退费回执驱动退号，refundNo=" + payload.refundNo();
        if (appointment.getStatus() == ApptStatus.TAKEN) {
            rollbackTakenVisit(appointment, reason);
        }
        events.publishEvent(new OutpatientDomainEvent(
                OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED,
                new AppointmentCancelledPayload(appointment.getApptNo(), appointment.getPatientId(), reason, true)));
        log.info(
                "退号退费回执终态完成：apptNo={}，patientId={}，refundNo={}，from={}，visitId={}",
                appointment.getApptNo(),
                appointment.getPatientId(),
                payload.refundNo(),
                from,
                appointment.getVisitId());
    }

    // ---------------------------------------------------------------- 爽约信用管理（Task 6）

    /**
     * 爽约信用记录查询（按患者维度，id 降序最新在前）：工作站信用管理列表消费面。
     *
     * @param patientId 患者主索引，非空
     * @return 信用记录出参列表（id 降序）；无记录返回空列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<ApptCreditVO> creditsByPatient(long patientId) {
        // 数据库读操作：患者维度信用台账（id 降序=A.4.3-17 唯一顺序，最新在前）
        return apptCreditRecordMapper
                .selectList(Wrappers.<ApptCreditRecord>lambdaQuery()
                        .eq(ApptCreditRecord::getPatientId, patientId)
                        .orderByDesc(ApptCreditRecord::getId))
                .stream()
                .map(AppointmentServiceImpl::toCreditVO)
                .toList();
    }

    /**
     * 爽约限约手工解除：restrict_to 提前至今日-1（即时失效）+release_reason 留痕（@AuditLog WRITE
     * 端点承载审计，updated_by 取操作者上下文）。仅在效限约可解除（无限约区间/已过期拒绝）。
     *
     * @param id     信用记录主键，非空
     * @param reason 解除理由，非空白
     * @return 解除后的信用记录出参，非空
     * @throws BizException OP-1009（404 记录不存在 / 409 无在效限约区间）时触发
     */
    @Override
    @Transactional
    public ApptCreditVO releaseCredit(long id, String reason) {
        // 数据库读操作：信用行定位
        ApptCreditRecord record = apptCreditRecordMapper.selectById(id);
        if (record == null) {
            throw new BizException(
                    OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED, HttpStatus.NOT_FOUND, "信用记录不存在：id=" + id);
        }
        if (record.getRestrictTo() == null || record.getRestrictTo().isBefore(LocalDate.now())) {
            throw new BizException(
                    OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "无限约区间或限约已失效，无需解除：id=" + id);
        }
        // 数据库写操作：提前解除（restrict_to 拨至今日-1 即时失效）+解除理由留痕
        record.setRestrictTo(LocalDate.now().minusDays(1));
        record.setReleaseReason(reason);
        record.setUpdatedBy(OperatorContextHolder.get());
        apptCreditRecordMapper.updateById(record);
        log.info(
                "爽约限约手工解除：creditId={}，patientId={}，原限约至={}，reason={}",
                id,
                record.getPatientId(),
                record.getRestrictTo(),
                reason);
        return toCreditVO(record);
    }

    // ---------------------------------------------------------------- 私有辅助

    /**
     * 池行占用 CAS（重读重试 ≤2 次）：0 行=version 前移/余量耗尽/停诊漂移，重读定性——停诊即 OP-1004、
     * 重试耗尽判 OP-1003 并回补 Redis 持有（防第一道闸漏扣）。
     *
     * @param pool      预约时读得的池行，非空
     * @param schedule  所属排班，非空（回池 TTL 锚）
     * @param redisHeld 第一道闸是否已扣减（true 时失败路径须 release 回补）
     * @throws BizException OP-1003（重试耗尽）/ OP-1004（重读发现停诊）
     */
    private void occupyPoolCasWithRetry(ApptNumberPool pool, Schedule schedule, boolean redisHeld) {
        int version = pool.getVersion() == null ? 0 : pool.getVersion();
        for (int attempt = 0; attempt <= CAS_RETRY_TIMES; attempt++) {
            if (attempt > 0) {
                // 重读定性：行漂移（停诊）即拒；余量耗尽提前终止；版本前移则携带新版本重试
                ApptNumberPool latest = apptNumberPoolMapper.selectById(pool.getId());
                if (latest == null || latest.getStatus() != PoolStatus.ACTIVE) {
                    releaseRedisHoldQuietly(pool, schedule, redisHeld);
                    throw new BizException(
                            OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED,
                            HttpStatus.CONFLICT,
                            "号源已停用（停诊联动），预约已回滚：poolId=" + pool.getId());
                }
                if (latest.getUsedCount() >= latest.getTotalQuota()) {
                    break;
                }
                version = latest.getVersion() == null ? 0 : latest.getVersion();
            }
            // 数据库写操作：池行条件更新（version 乐观锁 + 余量谓词，双道闸第二道）
            if (apptNumberPoolMapper.casOccupy(pool.getId(), version) == 1) {
                return;
            }
        }
        releaseRedisHoldQuietly(pool, schedule, redisHeld);
        throw new BizException(
                OutpatientErrorCode.POOL_EXHAUSTED, HttpStatus.CONFLICT, "号源不足（并发抢占）：poolId=" + pool.getId());
    }

    /**
     * PORTAL 占位登记（事务内执行——延迟信封属「占位登记」动作的例外语义，见 DelayEnvelopeSender 裁决链）：
     * 写支付占位键（TTL=支付时限+5min 缓冲）+ 投递延迟信封 + 发布 appointment.booked。
     *
     * @param appointment 已落库预约单（RESERVED+payDeadline），非空
     * @param pool        号源池行，非空（载荷 poolId 锚）
     * @param schedule    所属排班，非空（失败回补 TTL 锚与 booked 载荷 session 组件）
     * @param redisHeld   第一道闸是否已扣减（延迟信封投递失败须回补后整单回滚）
     */
    private void holdPortalSlot(Appointment appointment, ApptNumberPool pool, Schedule schedule, boolean redisHeld) {
        String apptNo = appointment.getApptNo();
        try {
            // 缓存写操作：支付占位键（辅助标记，权威时限守卫在 casTake 谓词与延迟档位）
            redisTemplate
                    .opsForValue()
                    .set(
                            payHoldKey(apptNo),
                            apptNo,
                            properties.appointmentTimeout().plus(PAY_HOLD_BUFFER));
        } catch (DataAccessException e) {
            log.error("支付占位键写入失败（占位守卫以延迟档位为权威）：apptNo={}，原因={}", apptNo, e.getMessage(), e);
        }
        try {
            // 事务内直发延迟信封（A.4.2-7 例外注记）；业务事件走事务内 publishEvent AFTER_COMMIT 出 MQ
            delayEnvelopeSender.send(new AppointmentTimeoutPayload(apptNo, appointment.getPatientId(), pool.getId()));
            events.publishEvent(new OutpatientDomainEvent(
                    OutpatientMessagingConstants.EVENT_APPOINTMENT_BOOKED,
                    new AppointmentBookedPayload(
                            apptNo,
                            appointment.getPatientId(),
                            appointment.getSchedDate().format(SEQ_DATE),
                            scheduleOf(appointment).getSession().getCode(),
                            appointment.getDeptCode(),
                            appointment.getApptType().getCode(),
                            appointment.getChannel().getCode())));
        } catch (RuntimeException e) {
            // 延迟信封是占位超时释放的权威载体：入队失败（AmqpException）则占位永不超时释放（槽位泄漏面），
            // 载荷组装失败（scheduleOf fail-fast）同面——对称裁定：先回补 Redis 持有+删占位键，再原样上抛
            // 交事务回滚（调用方可重试），与 CAS 耗尽/并发限购失败路径同款（R1 Important-1）
            releaseRedisHoldQuietly(pool, schedule, redisHeld);
            deletePayHoldQuietly(apptNo);
            log.error(
                    "portal 占位登记失败，预约整单回滚：apptNo={}，poolId={}，exception={}，原因={}",
                    apptNo,
                    pool.getId(),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
            throw e;
        }
        log.info(
                "portal 预约占位完成：apptNo={}，patientId={}，poolId={}，payDeadline={}",
                apptNo,
                appointment.getPatientId(),
                pool.getId(),
                appointment.getPayDeadline());
    }

    /**
     * 当日挂号一步直达 TAKEN：签发 visit_id → casTake（RESERVED→TAKEN+visit_id 回填）→ visit 落库
     * → 发布 visit.registered（M13 医保就诊登记依据）。
     *
     * @param appointment 已落库预约单（RESERVED），非空
     * @param pool        号源池行，非空（就诊类型派生锚）
     * @param schedule    所属排班，非空（doctor_id 关联锚）
     * @param operator    操作者标识，非空
     * @return 预约单出参（TAKEN+visitId），非空
     */
    private AppointmentVO registerVisitAndTake(
            Appointment appointment, ApptNumberPool pool, Schedule schedule, String operator) {
        String visitId = visitIdIssuer.issue();
        if (appointmentMapper.casTake(appointment.getId(), visitId) == 0) {
            // 新建行 CAS 落败属数据异常（行状态被并发篡改），fail-fast 回滚整单（含 visit 签发流水跳号，无副作用）
            throw new IllegalStateException("当日挂号 casTake 落败（新建行状态异常）：apptNo=" + appointment.getApptNo());
        }
        Visit visit = buildVisit(appointment, visitId, pool, schedule, operator);
        visitMapper.insert(visit);
        appointment.setStatus(ApptStatus.TAKEN);
        appointment.setVisitId(visitId);
        publishVisitRegistered(visit, schedule);
        log.info(
                "当日挂号完成：apptNo={}，visitId={}，patientId={}，deptCode={}，channel={}",
                appointment.getApptNo(),
                visitId,
                appointment.getPatientId(),
                appointment.getDeptCode(),
                appointment.getChannel().getCode());
        return toAppointmentVO(appointment);
    }

    /**
     * 构建 visit 实体（挂号初始 REGISTERED；就诊类型按 P1 口径派生：急诊号别→EMERGENCY，其余→GENERAL，
     * 复诊号别经 is_revisit 表达；triage_level/insurance_type 留空由分诊台任务写入）。
     *
     * @param appointment 关联预约单，非空
     * @param visitId     已签发就诊号，非空
     * @param pool        号源池行，非空
     * @param schedule    所属排班，非空
     * @param operator    操作者标识，非空
     * @return visit 实体（未落库），非空
     */
    private Visit buildVisit(
            Appointment appointment, String visitId, ApptNumberPool pool, Schedule schedule, String operator) {
        Visit visit = new Visit();
        visit.setVisitId(visitId);
        visit.setPatientId(appointment.getPatientId());
        visit.setApptId(appointment.getId());
        visit.setDeptCode(appointment.getDeptCode());
        visit.setDoctorId(schedule.getDoctorId());
        visit.setVisitType(pool.getApptType() == ApptType.EMERGENCY ? VisitType.EMERGENCY : VisitType.GENERAL);
        visit.setIsRevisit((short) (pool.getApptType() == ApptType.REVISIT ? 1 : 0));
        visit.setGreenChannelFlag((short) 0);
        visit.setRegisteredAt(OffsetDateTime.now());
        visit.setStatus(VisitStatus.REGISTERED);
        visit.setCreatedBy(operator);
        visit.setUpdatedBy(operator);
        return visit;
    }

    /**
     * 事务内发布 visit.registered 应用事件（AFTER_COMMIT 出 MQ，A.4.2-7；id 32 CF-3 签发锚）。
     *
     * @param visit   已落库就诊实体，非空
     * @param schedule 所属排班，非空（doctor_id 锚）
     */
    private void publishVisitRegistered(Visit visit, Schedule schedule) {
        events.publishEvent(new OutpatientDomainEvent(
                OutpatientMessagingConstants.EVENT_VISIT_REGISTERED,
                new VisitRegisteredPayload(
                        visit.getVisitId(),
                        visit.getPatientId(),
                        visit.getVisitType().getCode(),
                        visit.getDeptCode(),
                        schedule.getDoctorId())));
    }

    /**
     * 爽约信用记录落库（NO_SHOW）：窗口内既有计数+本次达阈值即写限约区间（今日 ~ 今日+restrictDays），
     * 预约侧 ② 拦截据此判定。
     *
     * @param patientId 归一后患者主索引，非空
     */
    private void recordNoShowCredit(long patientId) {
        // 数据库读操作：窗口内既有 NO_SHOW 计数（窗口=properties.noShowWindowDays，与拦截口径同源）
        long prior = apptCreditRecordMapper.selectCount(Wrappers.<ApptCreditRecord>lambdaQuery()
                .eq(ApptCreditRecord::getPatientId, patientId)
                .eq(ApptCreditRecord::getAction, ApptCreditRecord.ACTION_NO_SHOW)
                .ge(ApptCreditRecord::getOccurredAt, OffsetDateTime.now().minusDays(properties.noShowWindowDays())));
        boolean restrictHit = prior + 1 >= properties.noShowThreshold();
        ApptCreditRecord credit = new ApptCreditRecord();
        credit.setPatientId(patientId);
        credit.setAction(ApptCreditRecord.ACTION_NO_SHOW);
        credit.setOccurredAt(OffsetDateTime.now());
        credit.setWindowDays(properties.noShowWindowDays());
        if (restrictHit) {
            credit.setRestrictFrom(LocalDate.now());
            credit.setRestrictTo(LocalDate.now().plusDays(properties.restrictDays()));
        }
        credit.setCreatedBy(SYSTEM_OPERATOR);
        credit.setUpdatedBy(SYSTEM_OPERATOR);
        // 数据库写操作：信用行落库（每超时必记，禁二次落行由预约单 CAS 守卫承接）
        apptCreditRecordMapper.insert(credit);
        if (restrictHit) {
            log.warn(
                    "爽约信用达阈值，已写限约区间：patientId={}，窗口内 NO_SHOW 计数={}，restrictFrom=今日，restrictDays={}",
                    patientId,
                    prior + 1,
                    properties.restrictDays());
        }
    }

    /**
     * 操作者留痕取值：portal 匿名链路取哨兵 PORTAL（裁决 13，不经 OperatorContextHolder），
     * 窗口/自助链路取认证上下文操作者。
     *
     * @param channel 预约渠道，非空
     * @return 操作者标识，非空
     */
    private String operatorOf(ApptChannel channel) {
        return channel == ApptChannel.PORTAL ? PORTAL_SENTINEL : OperatorContextHolder.get();
    }

    /**
     * 签发预约单业务号（AP+yyyyMMdd+6 位流水）：Redis INCR 当日键 fy:outpatient:appt-seq:{yyyyMMdd}
     * （visit-seq 同型，A.5-1；首签续期 TTL=48h）。
     *
     * @return 预约单号原文，非空
     * @throws IllegalStateException Redis 流水返回空（连接异常由底层异常上抛），预约无法落号
     */
    private String issueApptNo() {
        String today = LocalDate.now().format(SEQ_DATE);
        String seqKey = APPT_SEQ_KEY_PREFIX + today;
        Long seq = redisTemplate.opsForValue().increment(seqKey);
        if (seq == null) {
            throw new IllegalStateException("预约单号签发失败：Redis 流水返回空，seqKey=" + seqKey);
        }
        if (seq == 1L) {
            redisTemplate.expire(seqKey, APPT_SEQ_KEY_TTL);
        }
        return "AP" + today + String.format("%06d", seq);
    }

    /**
     * 失败路径回补 Redis 持有（casOccupy 重试耗尽/停诊漂移）：仅第一道闸已扣减时执行；Redis 异常
     * 不回滚预约主链（池行为权威库存），error 留痕交日对账兜底收敛。
     *
     * @param pool      池行，非空
     * @param schedule  排班，非空（TTL 锚）
     * @param redisHeld 是否已扣减
     */
    private void releaseRedisHoldQuietly(ApptNumberPool pool, Schedule schedule, boolean redisHeld) {
        if (!redisHeld) {
            return;
        }
        try {
            long remain =
                    poolRedisGate.release(pool.getId(), pool.getTotalQuota(), poolKeyTtl(schedule.getSchedDate()));
            log.info("预约失败回补 Redis 持有：poolId={}，池键余量={}（-1=键缺失已跳过）", pool.getId(), remain);
        } catch (DataAccessException e) {
            log.error("预约失败回补 Redis 持有异常（日对账兜底）：poolId={}，原因={}", pool.getId(), e.getMessage(), e);
        }
    }

    /**
     * 回补池键余量（超时释放成功路径）：Redis 异常不阻断释放面（池行为权威库存），error 留痕交日对账。
     *
     * @param poolId    池行主键
     * @param total     池总量（越界封顶锚）
     * @param schedDate 排班日期（TTL 锚）
     * @param apptNo    预约单号（日志业务锚点）
     */
    private void releasePoolKeyQuietly(long poolId, long total, LocalDate schedDate, String apptNo) {
        try {
            long remain = poolRedisGate.release(poolId, total, poolKeyTtl(schedDate));
            log.info("超时回池 Redis 快路径已回补：apptNo={}，poolId={}，池键余量={}（-1=键缺失已跳过）", apptNo, poolId, remain);
        } catch (DataAccessException e) {
            log.error("超时回池 Redis 快路径回补失败（日对账兜底）：apptNo={}，poolId={}，原因={}", apptNo, poolId, e.getMessage(), e);
        }
    }

    /**
     * 删除支付占位键（取号/超时释放路径）：Redis 异常不阻断主链（TTL 兜底自然过期），error 留痕。
     *
     * @param apptNo 预约单号，非空
     */
    private void deletePayHoldQuietly(String apptNo) {
        try {
            redisTemplate.delete(payHoldKey(apptNo));
        } catch (DataAccessException e) {
            log.error("支付占位键删除失败（TTL 兜底自然过期）：apptNo={}，原因={}", apptNo, e.getMessage(), e);
        }
    }

    /**
     * 拼装支付占位键：fy:outpatient:pay-hold:{apptNo}（A.5-1）。
     *
     * @param apptNo 预约单号，非空
     * @return 占位键文本，非空
     */
    private static String payHoldKey(String apptNo) {
        return PAY_HOLD_KEY_PREFIX + apptNo;
    }

    /**
     * 池键 TTL 计算：排班日次日 02:00 对账窗口缓冲（A.5-1，ScheduleServiceImpl 同锚同口径）。
     *
     * @param schedDate 排班日期，非空
     * @return 距锚点时刻的时长（预约面向未来排班，恒为正）
     */
    private Duration poolKeyTtl(LocalDate schedDate) {
        return Duration.between(LocalDateTime.now(), schedDate.plusDays(1).atTime(POOL_KEY_TTL_ANCHOR));
    }

    /**
     * 按预约单冗余列反查排班（booked 载荷 session 组件来源；冗余列由本服务写入，理论必命中）。
     *
     * @param appointment 预约单，非空
     * @return 排班实体，非空
     * @throws IllegalStateException 排班定位失败（数据异常）
     */
    private Schedule scheduleOf(Appointment appointment) {
        Schedule schedule = scheduleMapper.selectById(appointment.getScheduleId());
        if (schedule == null) {
            throw new IllegalStateException("booked 事件组装失败：排班定位缺失，scheduleId=" + appointment.getScheduleId());
        }
        return schedule;
    }

    // ---------------------------------------------------------------- 退号/改期私有辅助（Task 6）

    /**
     * 已取号未报到退号（分支 3）：visit 必须仍处 REGISTERED（已报到/已接诊拒线上退 OP-1010，窗口
     * 人工「未诊即退」线下承载 Spec :137）——命中即走退费链并保持 TAKEN 占位待回执（visit 回滚随
     * billing.refund.approved 回执，{@link #rollbackTakenVisit} 承载）。
     *
     * @param appointment 已取号预约单，非空
     * @param reason      退号原因，非空白
     * @return 预约单出参（TAKEN 占位待回执），非空
     * @throws BizException OP-1010（visit 非 REGISTERED——已报到/已接诊）时触发
     * @throws IllegalStateException visit 按 visit_id 定位失败（数据异常，交事务回滚留痕）
     */
    private AppointmentVO cancelTaken(Appointment appointment, String reason) {
        // 数据库读操作：visit 态核验（TAKEN 单必有 visit 锚，缺失即数据异常 fail-fast）
        Visit visit =
                visitMapper.selectOne(Wrappers.<Visit>lambdaQuery().eq(Visit::getVisitId, appointment.getVisitId()));
        if (visit == null) {
            throw new IllegalStateException("退号失败：就诊记录缺失（数据异常）：apptNo=" + appointment.getApptNo());
        }
        if (visit.getStatus() != VisitStatus.REGISTERED) {
            log.warn(
                    "已报到/已接诊拒线上退：apptNo={}，visitId={}，visitStatus={}",
                    appointment.getApptNo(),
                    visit.getVisitId(),
                    visit.getStatus().getCode());
            throw new BizException(
                    OutpatientErrorCode.CANCEL_WINDOW_CLOSED,
                    HttpStatus.CONFLICT,
                    "已报到/已接诊不可线上退号，请到窗口按「未诊即退」规则办理：apptNo=" + appointment.getApptNo());
        }
        triggerRegistrationRefund(appointment, reason);
        return toAppointmentVO(appointment);
    }

    /**
     * 已预约占位退号（分支 1/2 分流）：已支付且有结算锚→分支 2 退费链（保持 RESERVED 待回执，占位
     * 不动）；否则→分支 1 免退费直取消（状态 CAS+回池+删占位键+cancelled 事件，无结算可退不涉
     * billing——裁决 7 字面）。
     *
     * @param appointment RESERVED 预约单，非空
     * @param reason      退号原因，非空白
     * @return 预约单出参，非空
     */
    private AppointmentVO cancelReserved(Appointment appointment, String reason) {
        if (appointment.getFeeStatus() == FeeStatusType.PAID && appointment.getFeeSettlementId() != null) {
            triggerRegistrationRefund(appointment, reason);
            return toAppointmentVO(appointment);
        }
        // 分支 1：状态 CAS 幂等守卫（0 行=并发已迁移/已超时释放，info 跳过禁二次回池）
        int flipped = appointmentMapper.casStatus(
                appointment.getId(), ApptStatus.RESERVED.getCode(), ApptStatus.CANCELLED.getCode());
        if (flipped == 0) {
            Appointment current = appointmentMapper.selectById(appointment.getId());
            log.info(
                    "退号幂等跳过（当前态 {}）：apptNo={}",
                    current == null ? "UNKNOWN" : current.getStatus().getCode(),
                    appointment.getApptNo());
            return toAppointmentVO(appointment);
        }
        appointment.setStatus(ApptStatus.CANCELLED);
        releasePoolConditionally(appointment.getPoolId(), appointment.getApptNo());
        deletePayHoldQuietly(appointment.getApptNo());
        events.publishEvent(new OutpatientDomainEvent(
                OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED,
                new AppointmentCancelledPayload(appointment.getApptNo(), appointment.getPatientId(), reason, false)));
        log.info(
                "退号完成（免退费路径）：apptNo={}，patientId={}，poolId={}，reason={}",
                appointment.getApptNo(),
                appointment.getPatientId(),
                appointment.getPoolId(),
                reason);
        return toAppointmentVO(appointment);
    }

    /**
     * 触发挂号费退费（分支 2/3 共用退费链）：可退行勾选后经 billing 端口 applyRefund（统一免审档，
     * 裁决 7）——appointment 保持原态占位待回执，号源/占位键均不动（终态一律 refund.approved 后置，
     * 回执前重复退号发起由 M13 超可退守卫拦截）。
     *
     * @param appointment 已支付预约单（RESERVED/TAKEN），非空
     * @param reason      退号原因，非空白（透传 M13 退费理由留痕）
     */
    private void triggerRegistrationRefund(Appointment appointment, String reason) {
        long refundId = billingPort.applyRefund(
                new VisitRefundCommand(appointment.getFeeSettlementId(), refundableLines(appointment), reason));
        log.info(
                "退号退费申请已触发，预约单保持 {} 待 refund.approved 回执：apptNo={}，refundId={}，settlementId={}",
                appointment.getStatus().getCode(),
                appointment.getApptNo(),
                refundId,
                appointment.getFeeSettlementId());
    }

    /**
     * 可退费用行勾选（visit 维度全状态查询→结算锚+可退态过滤）：行态词表与 M13 退费守卫同源
     * （SETTLED/PART_REFUND）；挂号费整号退=逐行数量 "1"（P1 挂号费单次计费口径）。无可退行即
     * 数据异常 fail-fast（PAID 单必有可退行，禁静默零退费）。
     *
     * @param appointment 已支付预约单，非空
     * @return 退费明细行清单（非空）
     * @throws IllegalStateException 可退行为空（数据异常）时触发
     */
    private List<VisitRefundCommand.Line> refundableLines(Appointment appointment) {
        List<VisitFeeView> fees = billingPort.feesByVisit(appointment.getVisitId());
        List<VisitRefundCommand.Line> lines = fees.stream()
                .filter(fee -> appointment.getFeeSettlementId().equals(fee.settlementId())
                        && REFUNDABLE_FEE_STATUSES.contains(fee.status()))
                .map(fee -> new VisitRefundCommand.Line(fee.feeId(), REGISTRATION_REFUND_QUANTITY))
                .toList();
        if (lines.isEmpty()) {
            throw new IllegalStateException("已支付预约单无可退费用行（数据异常）：apptNo=" + appointment.getApptNo() + "，settlementId="
                    + appointment.getFeeSettlementId());
        }
        return lines;
    }

    /**
     * 构建改期新预约单（RESERVED+reschedule_of 链锚）：patient/渠道承继旧单，排班/号段/冗余列取
     * 新池与新排班，费态回 UNPAID（改期仅承载未支付占位迁移），PORTAL 渠道重置完整支付时限。
     *
     * @param old         旧预约单，非空
     * @param newPool     新号源池行，非空
     * @param newSchedule 新排班日历，非空
     * @return 新预约单实体（未落库），非空
     */
    private Appointment buildRescheduledAppointment(Appointment old, ApptNumberPool newPool, Schedule newSchedule) {
        Appointment fresh = new Appointment();
        fresh.setApptNo(issueApptNo());
        fresh.setPatientId(old.getPatientId());
        fresh.setScheduleId(newSchedule.getId());
        fresh.setPoolId(newPool.getId());
        fresh.setDeptCode(newSchedule.getDeptCode());
        fresh.setApptType(newPool.getApptType());
        fresh.setSchedDate(newSchedule.getSchedDate());
        fresh.setSlotStart(newPool.getSlotStart());
        fresh.setSlotEnd(newPool.getSlotEnd());
        fresh.setChannel(old.getChannel());
        fresh.setFeeStatus(FeeStatusType.UNPAID);
        fresh.setPayDeadline(
                old.getChannel() == ApptChannel.PORTAL
                        ? OffsetDateTime.now().plus(properties.appointmentTimeout())
                        : null);
        fresh.setRescheduleOf(old.getApptNo());
        fresh.setStatus(ApptStatus.RESERVED);
        fresh.setCreatedBy(operatorOf(old.getChannel()));
        fresh.setUpdatedBy(operatorOf(old.getChannel()));
        return fresh;
    }

    /**
     * 改期 PORTAL 占位登记（占位键+延迟信封随新单号，占位时限=完整支付时限+缓冲）：登记失败对称
     * 回补（Redis 持有+新占位键）后原样上抛交事务回滚——与预约主链 holdPortalSlot 同款裁决面
     * （延迟信封是占位超时释放的权威载体，入队失败即槽位泄漏面）。旧单延迟信封到期时旧单已
     * CANCELLED，markTimeout 状态 CAS 守卫幂等跳过，零二次释放。
     *
     * @param fresh       已落库新预约单，非空
     * @param newPool     新号源池行，非空
     * @param newSchedule 新排班日历，非空（失败回补 TTL 锚）
     * @param redisHeld   第一道闸是否已扣减（true 时失败路径须 release 回补）
     */
    private void registerRescheduledHold(
            Appointment fresh, ApptNumberPool newPool, Schedule newSchedule, boolean redisHeld) {
        try {
            // 缓存写操作：新单支付占位键（辅助标记，权威时限守卫在延迟档位与 casTake 谓词）
            redisTemplate
                    .opsForValue()
                    .set(
                            payHoldKey(fresh.getApptNo()),
                            fresh.getApptNo(),
                            properties.appointmentTimeout().plus(PAY_HOLD_BUFFER));
            // 事务内直发延迟信封（A.4.2-7 例外注记，见 DelayEnvelopeSender 裁决链）
            delayEnvelopeSender.send(
                    new AppointmentTimeoutPayload(fresh.getApptNo(), fresh.getPatientId(), newPool.getId()));
        } catch (RuntimeException e) {
            releaseRedisHoldQuietly(newPool, newSchedule, redisHeld);
            deletePayHoldQuietly(fresh.getApptNo());
            log.error(
                    "改期占位登记失败，整单回滚：apptNo={}，newPoolId={}，exception={}，原因={}",
                    fresh.getApptNo(),
                    newPool.getId(),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    /**
     * 退号回执 visit 回滚（TAKEN 单，红线 5）：REGISTERED→CANCELLED 合法迁移对经 visit CAS 单步
     * 原子，命中后 visit_status_log 每迁必记+visit.cancelled 事件发布；并发落败 warn 跳过（后续
     * 重复回执对 CANCELLED 后的 appointment 零命中即自然幂等收敛）。
     *
     * @param appointment 已置 CANCELLED 的 TAKEN 预约单，非空
     * @param reason      迁移原因（回执锚），非空
     * @throws IllegalStateException visit 按 visit_id 定位失败（数据异常，交死信留痕）
     */
    private void rollbackTakenVisit(Appointment appointment, String reason) {
        // 数据库读操作：visit 锚定位（TAKEN 单必有 visit，缺失即数据异常）
        Visit visit =
                visitMapper.selectOne(Wrappers.<Visit>lambdaQuery().eq(Visit::getVisitId, appointment.getVisitId()));
        if (visit == null) {
            throw new IllegalStateException("退号回执回滚失败：就诊记录缺失（数据异常）：apptNo=" + appointment.getApptNo());
        }
        // visit 状态机合法迁移 REGISTERED→CANCELLED：CAS 命中才记日志与事件（并发已迁移幂等跳过）
        if (visitMapper.casStatus(visit.getId(), VisitStatus.REGISTERED.getCode(), VisitStatus.CANCELLED.getCode())
                == 0) {
            log.warn("退号回执 visit 回滚 CAS 落败（并发已迁移）：visitId={}，apptNo={}", visit.getVisitId(), appointment.getApptNo());
            return;
        }
        VisitStatusLog statusLog = new VisitStatusLog();
        statusLog.setVisitId(visit.getVisitId());
        statusLog.setFromStatus(VisitStatus.REGISTERED);
        statusLog.setToStatus(VisitStatus.CANCELLED);
        statusLog.setReason(reason);
        statusLog.setOperator(SYSTEM_OPERATOR);
        // 数据库写操作：迁移日志每迁必记（红线 5）
        visitStatusLogMapper.insert(statusLog);
        events.publishEvent(new OutpatientDomainEvent(
                OutpatientMessagingConstants.EVENT_VISIT_CANCELLED,
                new VisitCancelledPayload(visit.getVisitId(), visit.getPatientId(), reason)));
        log.info("退号回执 visit 回滚完成：visitId={}，apptNo={}，reason={}", visit.getVisitId(), appointment.getApptNo(), reason);
    }

    /**
     * 池行条件回池（超时释放/退号取消/改期放旧/退费回执四处共用释放面）：casRelease 带重读 version
     * 谓词（防并发双回补），1 行后回补 Redis 快路径（排班定位失败交日对账）；0 行重读定性后终止
     * 本轮回池——禁负余量、禁重复回补 Redis（Task 4 casRelease 谓词口径）。
     *
     * @param poolId 池行主键，非空
     * @param apptNo 预约单号（日志业务锚点），非空
     */
    private void releasePoolConditionally(long poolId, String apptNo) {
        ApptNumberPool pool = apptNumberPoolMapper.selectById(poolId);
        if (pool == null) {
            log.warn("回池跳过（池行不存在）：apptNo={}，poolId={}", apptNo, poolId);
            return;
        }
        int released = apptNumberPoolMapper.casRelease(pool.getId(), pool.getVersion() == null ? 0 : pool.getVersion());
        if (released != 1) {
            ApptNumberPool latest = apptNumberPoolMapper.selectById(poolId);
            log.warn(
                    "回池并发落败，终止本轮回池（重读定性 poolStatus={}）：apptNo={}，poolId={}",
                    latest == null ? "UNKNOWN" : latest.getStatus().getCode(),
                    apptNo,
                    poolId);
            return;
        }
        Schedule schedule = scheduleMapper.selectById(pool.getScheduleId());
        if (schedule == null) {
            log.warn("回池 Redis 快路径跳过（排班定位失败，交日对账）：apptNo={}，poolId={}", apptNo, poolId);
            return;
        }
        releasePoolKeyQuietly(pool.getId(), pool.getTotalQuota(), schedule.getSchedDate(), apptNo);
    }

    /** 实体 → 信用记录出参投影（Task 6 管理面）。 */
    private static ApptCreditVO toCreditVO(ApptCreditRecord record) {
        return new ApptCreditVO(
                record.getId(),
                record.getPatientId(),
                record.getAction(),
                record.getOccurredAt(),
                record.getWindowDays(),
                record.getRestrictFrom(),
                record.getRestrictTo(),
                record.getReleaseReason());
    }

    /**
     * 实体 → 预约单出参投影。
     *
     * @param appointment 预约单实体，非空
     * @return 预约单出参，非空
     */
    private AppointmentVO toAppointmentVO(Appointment appointment) {
        return new AppointmentVO(
                appointment.getId(),
                appointment.getApptNo(),
                appointment.getPatientId(),
                appointment.getScheduleId(),
                appointment.getPoolId(),
                appointment.getApptType(),
                appointment.getSchedDate(),
                appointment.getSlotStart(),
                appointment.getSlotEnd(),
                appointment.getChannel(),
                appointment.getFeeStatus(),
                appointment.getPayDeadline(),
                appointment.getVisitId(),
                appointment.getStatus());
    }

    /**
     * 实体 → 就诊记录出参投影。
     *
     * @param visit 就诊实体，非空
     * @return 就诊记录出参，非空
     */
    private VisitVO toVisitVO(Visit visit) {
        return new VisitVO(
                visit.getId(),
                visit.getVisitId(),
                visit.getPatientId(),
                visit.getApptId(),
                visit.getDeptCode(),
                visit.getDoctorId(),
                visit.getVisitType(),
                visit.getIsRevisit(),
                visit.getTriageLevel(),
                visit.getStatus(),
                visit.getRegisteredAt());
    }
}
