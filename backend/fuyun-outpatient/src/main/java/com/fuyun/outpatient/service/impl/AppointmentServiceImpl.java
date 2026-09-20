package com.fuyun.outpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.AppointmentBookedPayload;
import com.fuyun.outpatient.api.AppointmentTimeoutPayload;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.api.VisitRegisteredPayload;
import com.fuyun.outpatient.cache.PoolRedisGate;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.dto.AppointmentCreateRequest;
import com.fuyun.outpatient.entity.Appointment;
import com.fuyun.outpatient.entity.ApptCreditRecord;
import com.fuyun.outpatient.entity.ApptNumberPool;
import com.fuyun.outpatient.entity.Schedule;
import com.fuyun.outpatient.entity.Visit;
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
import com.fuyun.outpatient.properties.OutpatientProperties;
import com.fuyun.outpatient.service.IAppointmentService;
import com.fuyun.outpatient.service.IVisitIdIssuer;
import com.fuyun.outpatient.vo.AppointmentVO;
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
 * 预约/当日挂号服务实现（M03 FU-M03-02/03，Task 5 写路径唯一入口）：统一预约主流程七步——①患者
 * 归一+冻结拦截（PatientContextResolver，业务以 resolvedPatientId 关联）→②爽约限约拦截（信用窗口
 * NO_SHOW 计数达阈值且限约区间覆盖今日）→③限购拦截（同日同科 RESERVED/TAKEN 命中即拒，uk_appt_patient
 * 为最终防线）→④池行复核（ACTIVE+余量谓词，停诊 OP-1004）→⑤Redis 预扣（双道闸第一道：Lua 余量不足
 * -1 判 OP-1003 直接拒绝不降级——余量谓词旁路即超卖面；仅键缺失/Redis 异常走 DB 条件更新降级，warn
 * 留痕）→⑥appointment 落库+池行 casOccupy（同事务双道闸第二道，0 行重读重试 ≤2 次后判 OP-1003 并
 * 回补 Redis 持有）→⑦渠道分流：PORTAL 写 pay_deadline 占位+延迟信封入队（事务内直发例外，见
 * DelayEnvelopeSender 裁决链）+发布 appointment.booked；WINDOW/KIOSK 一步直达 TAKEN（同事务签发
 * visit+casTake+发布 visit.registered）。事件发布走事务内 publishEvent → AFTER_COMMIT 出 MQ（A.4.2-7）。
 * 资金无涉红线（裁决 7）：本类零金额逻辑。线程安全：无状态单例。装配归 OutpatientWebConfig
 *
 * @Import；com.fuyun.outpatient.service.impl 包 = JaCoCo PACKAGE LINE 1.00 覆盖对象。
 */
@Slf4j
public class AppointmentServiceImpl implements IAppointmentService {

    /** P1 开放预约渠道面（窗口/自助=当日挂号一步 TAKEN；portal=支付时限占位），其余词表位拒绝 */
    private static final Set<ApptChannel> P1_BOOK_CHANNELS =
            Set.of(ApptChannel.WINDOW, ApptChannel.KIOSK, ApptChannel.PORTAL);

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

    private final ApptCreditRecordMapper apptCreditRecordMapper;

    private final PoolRedisGate poolRedisGate;

    private final StringRedisTemplate redisTemplate;

    private final DelayEnvelopeSender delayEnvelopeSender;

    private final ApplicationEventPublisher events;

    private final OutpatientProperties properties;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import，backend 宪法 B.1）。
     *
     * @param patientContextResolver 患者上下文解析（patient api 契约），非空；归一主档+冻结拦截
     * @param visitIdIssuer          就诊号签发器，非空；CF-3 O 型 14 位签发
     * @param apptNumberPoolMapper   号源池行 mapper，非空；复核/占用 CAS/回补 CAS
     * @param scheduleMapper         排班日历 mapper，非空；dept_code/session/doctor_id 关联读
     * @param appointmentMapper      预约单 mapper，非空；落库/状态 CAS/取号 CAS
     * @param visitMapper            就诊记录 mapper，非空；挂号落库/就诊号定位
     * @param apptCreditRecordMapper 爽约信用 mapper，非空；窗口计数/信用行落库
     * @param poolRedisGate          号源 Redis 预扣闸，非空；双道闸第一道
     * @param redisTemplate          Redis 字符串模板，非空；预约单号流水键与支付占位键
     * @param delayEnvelopeSender    延迟信封发送器，非空；支付时限占位登记
     * @param events                 Spring 应用事件发布器，非空；事务内发布 AFTER_COMMIT 出 MQ
     * @param properties             门诊域参数，非空；支付时限/爽约窗口/阈值/限约天数
     */
    public AppointmentServiceImpl(
            PatientContextResolver patientContextResolver,
            IVisitIdIssuer visitIdIssuer,
            ApptNumberPoolMapper apptNumberPoolMapper,
            ScheduleMapper scheduleMapper,
            AppointmentMapper appointmentMapper,
            VisitMapper visitMapper,
            ApptCreditRecordMapper apptCreditRecordMapper,
            PoolRedisGate poolRedisGate,
            StringRedisTemplate redisTemplate,
            DelayEnvelopeSender delayEnvelopeSender,
            ApplicationEventPublisher events,
            OutpatientProperties properties) {
        this.patientContextResolver = patientContextResolver;
        this.visitIdIssuer = visitIdIssuer;
        this.apptNumberPoolMapper = apptNumberPoolMapper;
        this.scheduleMapper = scheduleMapper;
        this.appointmentMapper = appointmentMapper;
        this.visitMapper = visitMapper;
        this.apptCreditRecordMapper = apptCreditRecordMapper;
        this.poolRedisGate = poolRedisGate;
        this.redisTemplate = redisTemplate;
        this.delayEnvelopeSender = delayEnvelopeSender;
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
        // 池行回池（version 乐观锁条件更新，防并发双回补）：0 行=并发已回池（version 前移）或池行状态漂移，
        // 重读定性后终止本轮回池——禁负余量、禁重复回补 Redis（Task 4 casRelease 谓词与本消费点配套）
        ApptNumberPool pool = apptNumberPoolMapper.selectById(payload.poolId());
        if (pool == null) {
            log.warn("预约支付超时回池跳过（池行不存在）：apptNo={}，poolId={}", payload.apptNo(), payload.poolId());
        } else {
            int released =
                    apptNumberPoolMapper.casRelease(pool.getId(), pool.getVersion() == null ? 0 : pool.getVersion());
            if (released == 1) {
                Schedule schedule = scheduleMapper.selectById(pool.getScheduleId());
                if (schedule == null) {
                    log.warn("超时回池 Redis 快路径跳过（排班定位失败，交日对账）：apptNo={}，poolId={}", payload.apptNo(), pool.getId());
                } else {
                    releasePoolKeyQuietly(
                            pool.getId(), pool.getTotalQuota(), schedule.getSchedDate(), payload.apptNo());
                }
            } else {
                ApptNumberPool latest = apptNumberPoolMapper.selectById(payload.poolId());
                log.warn(
                        "超时回池并发落败，终止本轮回池（重读定性 poolStatus={}）：apptNo={}，poolId={}",
                        latest == null ? "UNKNOWN" : latest.getStatus().getCode(),
                        payload.apptNo(),
                        payload.poolId());
            }
        }
        deletePayHoldQuietly(payload.apptNo());
        recordNoShowCredit(payload.patientId());
        log.warn(
                "预约支付超时已置 NO_SHOW：apptNo={}，patientId={}，poolId={}",
                payload.apptNo(),
                payload.patientId(),
                payload.poolId());
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
