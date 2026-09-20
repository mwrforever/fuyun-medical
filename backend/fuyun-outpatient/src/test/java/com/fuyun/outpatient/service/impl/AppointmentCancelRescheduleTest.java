package com.fuyun.outpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.OutpatientBillingPort;
import com.fuyun.billing.api.RefundApprovedPayload;
import com.fuyun.billing.api.VisitFeeView;
import com.fuyun.billing.api.VisitRefundCommand;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.outpatient.api.AppointmentCancelledPayload;
import com.fuyun.outpatient.api.AppointmentRescheduledPayload;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.api.VisitCancelledPayload;
import com.fuyun.outpatient.cache.PoolRedisGate;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
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
import com.fuyun.outpatient.enums.ScheduleStatus;
import com.fuyun.outpatient.enums.SessionType;
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
import com.fuyun.patient.api.PatientContextResolver;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.verification.VerificationMode;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

/**
 * 退号退费联动与改期服务单测（M03 FU-M03-03，Task 6 冻结用例集 11 例+覆盖率收口补例）：
 * 退号四分支（时限内免退费/已付退费审批待回执/已取号退费待回执/已报到拒线上退）、线上退号时限
 * OP-1010（窗口渠道不受限）、refund.approved 回执驱动终态（appointment 分支：取消+回池+REFUNDED，
 * TAKEN 分支同步 visit 回滚+迁移日志）、改期先占新后退旧（reschedule_of 链）与信用手工解除。
 * AFTER_COMMIT 的 MQ 出线时机归 OutpatientEventPublisherTest 与集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class AppointmentCancelRescheduleTest {

    /** 签发日期段格式（yyyyMMdd） */
    private static final DateTimeFormatter SEQ_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    @Mock
    private PatientContextResolver patientContextResolver;

    @Mock
    private IVisitIdIssuer visitIdIssuer;

    @Mock
    private ApptNumberPoolMapper apptNumberPoolMapper;

    @Mock
    private ScheduleMapper scheduleMapper;

    @Mock
    private AppointmentMapper appointmentMapper;

    @Mock
    private VisitMapper visitMapper;

    @Mock
    private VisitStatusLogMapper visitStatusLogMapper;

    @Mock
    private ApptCreditRecordMapper apptCreditRecordMapper;

    @Mock
    private PoolRedisGate poolRedisGate;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private OutpatientBillingPort billingPort;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<OutpatientDomainEvent> eventCaptor;

    private IAppointmentService service;

    @BeforeAll
    static void initTableInfo() {
        // 预约单按业务号/结算锚定位、visit 按就诊号定位、信用行查询的 lambda 条件列解析依赖
        // TableInfo（容器外单测手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Appointment.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Visit.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApptCreditRecord.class);
    }

    @BeforeEach
    void setUp() {
        // 缺省参数（与 V204/V705 与 Properties @DefaultValue 同源）：支付时限 15m、线上退号提前 1 日
        OutpatientProperties properties = new OutpatientProperties(Duration.ofMinutes(15), 1, 90, 3, 90);
        DelayEnvelopeSender delayEnvelopeSender =
                new DelayEnvelopeSender(rabbitTemplate, new EventEnvelopeCodec(new ObjectMapper()));
        service = new AppointmentServiceImpl(
                patientContextResolver,
                visitIdIssuer,
                apptNumberPoolMapper,
                scheduleMapper,
                appointmentMapper,
                visitMapper,
                visitStatusLogMapper,
                apptCreditRecordMapper,
                poolRedisGate,
                redisTemplate,
                delayEnvelopeSender,
                billingPort,
                events,
                properties);
        OperatorContextHolder.set("admin001");
    }

    @AfterEach
    void tearDown() {
        OperatorContextHolder.clear();
    }

    // ---------------------------------------------------------------- 替身构造

    /** 旧号源池行替身（poolId=31，total=4、used=1、version 由入参定） */
    private ApptNumberPool oldPool(int version) {
        ApptNumberPool pool = new ApptNumberPool();
        pool.setId(31L);
        pool.setScheduleId(11L);
        pool.setApptType(ApptType.EXPERT);
        pool.setSlotStart(LocalTime.of(8, 0));
        pool.setSlotEnd(LocalTime.of(8, 30));
        pool.setTotalQuota(4);
        pool.setUsedCount(1);
        pool.setVersion(version);
        pool.setStatus(PoolStatus.ACTIVE);
        return pool;
    }

    /** 新号源池行替身（poolId=61，total=4、used=0、version=0；改期目标） */
    private ApptNumberPool newPool(PoolStatus status, int usedCount) {
        ApptNumberPool pool = new ApptNumberPool();
        pool.setId(61L);
        pool.setScheduleId(12L);
        pool.setApptType(ApptType.EXPERT);
        pool.setSlotStart(LocalTime.of(9, 0));
        pool.setSlotEnd(LocalTime.of(9, 30));
        pool.setTotalQuota(4);
        pool.setUsedCount(usedCount);
        pool.setVersion(0);
        pool.setStatus(status);
        return pool;
    }

    /** 旧排班日历替身（池行 31 所属：3 日后上午、DEP001/DOC001） */
    private Schedule oldSchedule() {
        return schedule(11L, LocalDate.now().plusDays(3), "DEP001", "DOC001");
    }

    /** 新排班日历替身（池行 61 所属：5 日后上午、DEP002/DOC002——异日异科规避 uk_appt_patient 限购） */
    private Schedule newSchedule() {
        return schedule(12L, LocalDate.now().plusDays(5), "DEP002", "DOC002");
    }

    /** 排班日历替身（按主键/日期/科室/医生定） */
    private Schedule schedule(long id, LocalDate schedDate, String deptCode, String doctorId) {
        Schedule schedule = new Schedule();
        schedule.setId(id);
        schedule.setTemplateId(1L);
        schedule.setSchedDate(schedDate);
        schedule.setSession(SessionType.MORNING);
        schedule.setDeptCode(deptCode);
        schedule.setDoctorId(doctorId);
        schedule.setApptType(ApptType.EXPERT);
        schedule.setTotalQuota(4);
        schedule.setUsedQuota(0);
        schedule.setStatus(ScheduleStatus.NORMAL);
        return schedule;
    }

    /**
     * 预约单替身（退号/改期/回执分支构造）。
     *
     * <p>PAID 单携 visitId：PAID 由 Task 10 收费联动经 visit 结算面回填（挂号费与就诊费同 visit
     * 结算），PAID ⇒ visit 锚在位为可实现不变式（分支 2 的 P1 语义见实现类 javadoc）。
     */
    private Appointment appointment(
            ApptStatus status,
            FeeStatusType feeStatus,
            Long feeSettlementId,
            ApptChannel channel,
            LocalDate schedDate,
            String visitId) {
        Appointment appointment = new Appointment();
        appointment.setId(101L);
        appointment.setApptNo("AP20260920000001");
        appointment.setPatientId(9L);
        appointment.setScheduleId(11L);
        appointment.setPoolId(31L);
        appointment.setDeptCode("DEP001");
        appointment.setApptType(ApptType.EXPERT);
        appointment.setSchedDate(schedDate);
        appointment.setSlotStart(LocalTime.of(8, 0));
        appointment.setSlotEnd(LocalTime.of(8, 30));
        appointment.setChannel(channel);
        appointment.setFeeStatus(feeStatus);
        appointment.setFeeSettlementId(feeSettlementId);
        appointment.setVisitId(visitId);
        appointment.setStatus(status);
        return appointment;
    }

    /** visit 替身（visit 表 PK=501，就诊号 O20260921000001，状态由入参定） */
    private Visit visit(VisitStatus status) {
        Visit visit = new Visit();
        visit.setId(501L);
        visit.setVisitId("O20260921000001");
        visit.setPatientId(9L);
        visit.setApptId(101L);
        visit.setDeptCode("DEP001");
        visit.setDoctorId("DOC001");
        visit.setVisitType(VisitType.GENERAL);
        visit.setIsRevisit((short) 0);
        visit.setStatus(status);
        return visit;
    }

    /** 挂号费费用行视图替身（billing 端口 feesByVisit 返回；SETTLED 可退） */
    private VisitFeeView settledFee(long feeId, long settlementId) {
        return new VisitFeeView(feeId, "SETTLED", "MANUAL-1", "MANUAL", 5000L, settlementId);
    }

    /** refund.approved 回执载荷替身（V605 id 20 七组件） */
    private RefundApprovedPayload refundReceipt() {
        return new RefundApprovedPayload(9001L, "R9001", 501L, 9L, 5000L, "DAY_CORRECTION", true);
    }

    /** 爽约信用行替身（action=NO_SHOW，限约区间由入参定） */
    private ApptCreditRecord credit(LocalDate restrictTo) {
        ApptCreditRecord record = new ApptCreditRecord();
        record.setId(77L);
        record.setPatientId(9L);
        record.setAction(ApptCreditRecord.ACTION_NO_SHOW);
        record.setOccurredAt(OffsetDateTime.now().minusDays(1));
        record.setWindowDays(90);
        record.setRestrictFrom(LocalDate.now().minusDays(1));
        record.setRestrictTo(restrictTo);
        return record;
    }

    /** 事件断言：单次发布捕获 */
    private OutpatientDomainEvent capturedEvent(VerificationMode mode) {
        verify(events, mode).publishEvent(eventCaptor.capture());
        return eventCaptor.getValue();
    }

    // ---------------------------------------------------------------- 退号四分支

    @Test
    @DisplayName(
            "cancel 分支 1：支付时限内未支付——CANCELLED+casRelease 回池+占位键删+cancelled 事件 feeRefundTriggered=false，零 billing 触达")
    void unpaidCancelReleasesPoolWithoutBillingCall() {
        Appointment unpaid = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(unpaid);
        when(appointmentMapper.casStatus(101L, "RESERVED", "CANCELLED")).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(oldPool(7));
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(1);
        when(scheduleMapper.selectById(11L)).thenReturn(oldSchedule());

        AppointmentVO vo = service.cancel("AP20260920000001", "行程变动取消");

        // 分支 1 断言：状态 CAS+回池（version 谓词）+Redis 快路径+占位键清理+免退费事件
        verify(apptNumberPoolMapper).casRelease(31L, 7);
        verify(poolRedisGate).release(eq(31L), eq(4L), any(Duration.class));
        verify(redisTemplate).delete("fy:outpatient:pay-hold:AP20260920000001");
        OutpatientDomainEvent event = capturedEvent(times(1));
        assertThat(event.eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED);
        assertThat(event.payload()).isInstanceOf(AppointmentCancelledPayload.class);
        AppointmentCancelledPayload payload = (AppointmentCancelledPayload) event.payload();
        assertThat(payload.apptNo()).isEqualTo("AP20260920000001");
        assertThat(payload.patientId()).isEqualTo(9L);
        assertThat(payload.reason()).isEqualTo("行程变动取消");
        assertThat(payload.feeRefundTriggered()).isFalse();
        // 无结算可退不涉 billing（裁决 7 字面）：端口零触达、零退费态回写
        verifyNoInteractions(billingPort);
        verify(appointmentMapper, never()).casMarkRefunded(anyLong());
        assertThat(vo.status()).isEqualTo(ApptStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancel 分支 2：已支付未取号——applyRefund 返回 901、appointment 保持 RESERVED 待回执、fee_status 不变")
    void paidCancelAppliesRefundViaPortAndWaitsReceipt() {
        Appointment paid = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.PAID,
                501L,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(paid);
        when(billingPort.feesByVisit("O20260921000001")).thenReturn(List.of(settledFee(1L, 501L)));
        when(billingPort.applyRefund(any(VisitRefundCommand.class))).thenReturn(901L);

        AppointmentVO vo = service.cancel("AP20260920000001", "行程变动取消");

        // 退费命令断言：结算锚 501+可退行逐条 Line(feeId,"1")+原因透传
        ArgumentCaptor<VisitRefundCommand> cmdCaptor = ArgumentCaptor.forClass(VisitRefundCommand.class);
        verify(billingPort).applyRefund(cmdCaptor.capture());
        VisitRefundCommand cmd = cmdCaptor.getValue();
        assertThat(cmd.settlementId()).isEqualTo(501L);
        assertThat(cmd.lines()).hasSize(1);
        assertThat(cmd.lines().get(0).feeId()).isEqualTo(1L);
        assertThat(cmd.lines().get(0).quantity()).isEqualTo("1");
        assertThat(cmd.reason()).isEqualTo("行程变动取消");
        // 回执前占位不动：零状态迁移、零回池、零费态回写、零事件（终态一律 refund.approved 后置）
        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(appointmentMapper, never()).casMarkRefunded(anyLong());
        verify(apptNumberPoolMapper, never()).casRelease(anyLong(), anyInt());
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
        assertThat(vo.status()).isEqualTo(ApptStatus.RESERVED);
        assertThat(vo.feeStatus()).isEqualTo(FeeStatusType.PAID);
    }

    @Test
    @DisplayName("cancel 分支 3：已取号未报到——applyRefund 调用且 visit 保持 REGISTERED（回执前不触 visit CAS 与迁移日志）")
    void takenCancelAppliesRefundAndWaitsReceipt() {
        Appointment taken = appointment(
                ApptStatus.TAKEN,
                FeeStatusType.PAID,
                501L,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(taken);
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.REGISTERED));
        when(billingPort.feesByVisit("O20260921000001")).thenReturn(List.of(settledFee(1L, 501L)));
        when(billingPort.applyRefund(any(VisitRefundCommand.class))).thenReturn(902L);

        AppointmentVO vo = service.cancel("AP20260920000001", "改乘其他交通");

        verify(billingPort).applyRefund(any(VisitRefundCommand.class));
        // 回执前不触 visit 终态面：零 CAS、零迁移日志、零 visit.cancelled
        verify(visitMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(visitStatusLogMapper, never()).insert(any(VisitStatusLog.class));
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
        assertThat(vo.status()).isEqualTo(ApptStatus.TAKEN);
    }

    @Test
    @DisplayName("cancel 分支 4：已报到（visit WAITING）拒 OP-1010——已报到/已接诊不可线上退（窗口人工未诊即退线下承载）")
    void cancelRejectsAfterCheckIn() {
        Appointment taken = appointment(
                ApptStatus.TAKEN,
                FeeStatusType.PAID,
                501L,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(taken);
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.WAITING));

        assertThatThrownBy(() -> service.cancel("AP20260920000001", "行程变动取消"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.CANCEL_WINDOW_CLOSED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(billingPort, never()).applyRefund(any(VisitRefundCommand.class));
        verify(visitMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("cancel 退号时限：PORTAL 线上渠道超窗拒 OP-1010（就诊日当日窗口已关）；同参 WINDOW 渠道放行（窗口不受限——分支 2 直走）")
    void cancelRejectsBeyondOnlineWindow() {
        // 线上渠道：sched_date=今日、onlineCancelBeforeDays=1 → 截止日=昨日已过 → OP-1010（偏差注记：
        // brief 原拟今日+3 在 Properties 冻结语义「就诊日前不足 N 天关闭」下不触发，测试参数按可触发值取今日）
        Appointment portalToday =
                appointment(ApptStatus.RESERVED, FeeStatusType.UNPAID, null, ApptChannel.PORTAL, LocalDate.now(), null);
        when(appointmentMapper.selectOne(any())).thenReturn(portalToday);
        assertThatThrownBy(() -> service.cancel("AP20260920000001", "行程变动取消"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.CANCEL_WINDOW_CLOSED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verifyNoInteractions(billingPort);

        // 窗口渠道不受限：同 sched_date=今日、已支付未取号 → 直走分支 2 退费链
        Appointment windowToday = appointment(
                ApptStatus.RESERVED, FeeStatusType.PAID, 501L, ApptChannel.WINDOW, LocalDate.now(), "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(windowToday);
        when(billingPort.feesByVisit("O20260921000001")).thenReturn(List.of(settledFee(1L, 501L)));
        when(billingPort.applyRefund(any(VisitRefundCommand.class))).thenReturn(903L);

        service.cancel("AP20260920000001", "窗口办理退号");

        verify(billingPort).applyRefund(any(VisitRefundCommand.class));
        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- refund.approved 回执驱动终态

    @Test
    @DisplayName("回执：RESERVED 单命中结算锚——CANCELLED+回池+fee_status=REFUNDED+cancelled 事件 feeRefundTriggered=true")
    void refundReceiptCancelsReservationAndReleasesPool() {
        Appointment reserved = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.PAID,
                501L,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(reserved);
        when(appointmentMapper.casStatus(101L, "RESERVED", "CANCELLED")).thenReturn(1);
        when(appointmentMapper.casMarkRefunded(101L)).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(oldPool(7));
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(1);
        when(scheduleMapper.selectById(11L)).thenReturn(oldSchedule());

        service.confirmRefundedCancel(refundReceipt());

        verify(appointmentMapper).casMarkRefunded(101L);
        verify(apptNumberPoolMapper).casRelease(31L, 7);
        verify(poolRedisGate).release(eq(31L), eq(4L), any(Duration.class));
        verify(redisTemplate).delete("fy:outpatient:pay-hold:AP20260920000001");
        OutpatientDomainEvent event = capturedEvent(times(1));
        assertThat(event.eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED);
        AppointmentCancelledPayload payload = (AppointmentCancelledPayload) event.payload();
        assertThat(payload.apptNo()).isEqualTo("AP20260920000001");
        assertThat(payload.feeRefundTriggered()).isTrue();
        // RESERVED 单零 visit 面
        verify(visitMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("回执：TAKEN 态——visit REGISTERED→CANCELLED+visit_status_log 一行（from/to）+visit.cancelled 与 cancelled 双事件")
    void refundReceiptRollsBackTakenVisitWithLog() {
        Appointment taken = appointment(
                ApptStatus.TAKEN,
                FeeStatusType.PAID,
                501L,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(taken);
        when(appointmentMapper.casStatus(101L, "TAKEN", "CANCELLED")).thenReturn(1);
        when(appointmentMapper.casMarkRefunded(101L)).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(oldPool(7));
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(1);
        when(scheduleMapper.selectById(11L)).thenReturn(oldSchedule());
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.REGISTERED));
        when(visitMapper.casStatus(501L, "REGISTERED", "CANCELLED")).thenReturn(1);

        service.confirmRefundedCancel(refundReceipt());

        verify(visitMapper).casStatus(501L, "REGISTERED", "CANCELLED");
        ArgumentCaptor<VisitStatusLog> logCaptor = ArgumentCaptor.forClass(VisitStatusLog.class);
        verify(visitStatusLogMapper).insert(logCaptor.capture());
        VisitStatusLog log = logCaptor.getValue();
        assertThat(log.getVisitId()).isEqualTo("O20260921000001");
        assertThat(log.getFromStatus()).isEqualTo(VisitStatus.REGISTERED);
        assertThat(log.getToStatus()).isEqualTo(VisitStatus.CANCELLED);
        // 双事件：visit.cancelled 先行（visit 回滚终态确认）、appointment.cancelled 后行（退号完成）
        verify(events, times(2)).publishEvent(eventCaptor.capture());
        List<OutpatientDomainEvent> published = eventCaptor.getAllValues();
        assertThat(published.get(0).eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_VISIT_CANCELLED);
        VisitCancelledPayload visitPayload =
                (VisitCancelledPayload) published.get(0).payload();
        assertThat(visitPayload.visitId()).isEqualTo("O20260921000001");
        assertThat(visitPayload.patientId()).isEqualTo(9L);
        assertThat(published.get(1).eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED);
        assertThat(((AppointmentCancelledPayload) published.get(1).payload()).feeRefundTriggered())
                .isTrue();
    }

    @Test
    @DisplayName("回执：settlementId 无命中（非退号退费链路）——info 幂等跳过零写零事件")
    void refundReceiptIgnoredForUnknownSettlement() {
        when(appointmentMapper.selectOne(any())).thenReturn(null);

        service.confirmRefundedCancel(refundReceipt());

        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(appointmentMapper, never()).casMarkRefunded(anyLong());
        verify(apptNumberPoolMapper, never()).casRelease(anyLong(), anyInt());
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("回执：状态 CAS 并发落败（重复回执/已迁移）——幂等跳过，零回池零费态回写零事件")
    void refundReceiptSkipsWhenCasLose() {
        Appointment reserved = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.PAID,
                501L,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(reserved);
        when(appointmentMapper.casStatus(101L, "RESERVED", "CANCELLED")).thenReturn(0);

        service.confirmRefundedCancel(refundReceipt());

        verify(appointmentMapper, never()).casMarkRefunded(anyLong());
        verify(apptNumberPoolMapper, never()).casRelease(anyLong(), anyInt());
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("回执：TAKEN 态 visit CAS 并发落败——跳过迁移日志与 visit.cancelled，appointment 终态照常收敛")
    void refundReceiptVisitCasLoseSkipsVisitTerminal() {
        Appointment taken = appointment(
                ApptStatus.TAKEN,
                FeeStatusType.PAID,
                501L,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(taken);
        when(appointmentMapper.casStatus(101L, "TAKEN", "CANCELLED")).thenReturn(1);
        when(appointmentMapper.casMarkRefunded(101L)).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(oldPool(7));
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(1);
        when(scheduleMapper.selectById(11L)).thenReturn(oldSchedule());
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.REGISTERED));
        when(visitMapper.casStatus(501L, "REGISTERED", "CANCELLED")).thenReturn(0);

        service.confirmRefundedCancel(refundReceipt());

        verify(visitStatusLogMapper, never()).insert(any(VisitStatusLog.class));
        OutpatientDomainEvent event = capturedEvent(times(1));
        assertThat(event.eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED);
    }

    @Test
    @DisplayName("回执：fee_status=REFUNDED 条件回写 0 行（费态漂移）——warn 不阻断，退号终态与事件照常收敛")
    void refundReceiptContinuesWhenFeeMarkLose() {
        Appointment reserved = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.PAID,
                501L,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(reserved);
        when(appointmentMapper.casStatus(101L, "RESERVED", "CANCELLED")).thenReturn(1);
        when(appointmentMapper.casMarkRefunded(101L)).thenReturn(0);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(oldPool(7));
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(1);
        when(scheduleMapper.selectById(11L)).thenReturn(oldSchedule());

        service.confirmRefundedCancel(refundReceipt());

        verify(apptNumberPoolMapper).casRelease(31L, 7);
        assertThat(capturedEvent(times(1)).eventType())
                .isEqualTo(OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED);
    }

    // ---------------------------------------------------------------- 改期（先占新后退旧）

    /** 改期链公共打桩：旧单 RESERVED/UNPAID/PORTAL（今日+3）→ 新池 61（5 日后）占新成功、放旧成功 */
    private void stubRescheduleHappyPath() {
        Appointment old = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(old);
        when(apptNumberPoolMapper.selectById(61L)).thenReturn(newPool(PoolStatus.ACTIVE, 0));
        when(scheduleMapper.selectById(12L)).thenReturn(newSchedule());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(5L);
        // 新池第一道闸预扣由各用例自定（3=持有在位 / -2=键缺失降级 / 异常=连接降级），此处不 stub
        doAnswer(inv -> {
                    inv.getArgument(0, Appointment.class).setId(102L);
                    return 1;
                })
                .when(appointmentMapper)
                .insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(61L, 0)).thenReturn(1);
        when(appointmentMapper.casStatus(101L, "RESERVED", "CANCELLED")).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(oldPool(7));
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(1);
        when(scheduleMapper.selectById(11L)).thenReturn(oldSchedule());
    }

    /** 改期降级链公共断言前置：新池第一道闸预扣成功（持有在位） */
    private void stubNewPoolDeductHeld() {
        when(poolRedisGate.deduct(eq(61L), eq(4L), any(Duration.class))).thenReturn(3);
    }

    @Test
    @DisplayName("改期：先占新后退旧——新池 casOccupy 先于旧池 casRelease（InOrder），新单 reschedule_of 链与 PORTAL 占位登记在位")
    void rescheduleOccupiesNewBeforeReleasingOld() {
        stubRescheduleHappyPath();
        stubNewPoolDeductHeld();

        AppointmentVO vo = service.reschedule("AP20260920000001", new RescheduleRequest(61L));

        // 新单落库断言：reschedule_of=旧单号、RESERVED、patientId 承继、新池/新排班冗余列
        ArgumentCaptor<Appointment> insertCaptor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentMapper).insert(insertCaptor.capture());
        Appointment inserted = insertCaptor.getValue();
        assertThat(inserted.getRescheduleOf()).isEqualTo("AP20260920000001");
        assertThat(inserted.getStatus()).isEqualTo(ApptStatus.RESERVED);
        assertThat(inserted.getPatientId()).isEqualTo(9L);
        assertThat(inserted.getPoolId()).isEqualTo(61L);
        assertThat(inserted.getDeptCode()).isEqualTo("DEP002");
        assertThat(inserted.getSchedDate()).isEqualTo(LocalDate.now().plusDays(5));
        assertThat(inserted.getFeeStatus()).isEqualTo(FeeStatusType.UNPAID);
        // InOrder 冻结断言：先占新（casOccupy 新池）后放旧（casRelease 旧池）——防两头空
        InOrder order = inOrder(apptNumberPoolMapper);
        order.verify(apptNumberPoolMapper).casOccupy(61L, 0);
        order.verify(apptNumberPoolMapper).casRelease(31L, 7);
        // 旧单终态 CAS 与占位键清理在位
        verify(appointmentMapper).casStatus(101L, "RESERVED", "CANCELLED");
        verify(redisTemplate).delete("fy:outpatient:pay-hold:AP20260920000001");
        // PORTAL 占位登记随新单：占位键+延迟信封（新单 apptNo/poolId）
        verify(valueOperations).set(eq("fy:outpatient:pay-hold:" + vo.apptNo()), eq(vo.apptNo()), any(Duration.class));
        verify(rabbitTemplate)
                .convertAndSend(
                        eq("fy.delay"), eq("delay.appointment-timeout"), any(Object.class), any(CorrelationData.class));
        assertThat(vo.poolId()).isEqualTo(61L);
        assertThat(vo.status()).isEqualTo(ApptStatus.RESERVED);
    }

    @Test
    @DisplayName("改期：WINDOW 渠道占位单——新单无支付时限（pay_deadline 空）且链事件照常发布")
    void rescheduleWindowChannelLeavesPayDeadlineEmpty() {
        Appointment old = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.WINDOW,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(old);
        when(apptNumberPoolMapper.selectById(61L)).thenReturn(newPool(PoolStatus.ACTIVE, 0));
        when(scheduleMapper.selectById(12L)).thenReturn(newSchedule());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(11L);
        stubNewPoolDeductHeld();
        doAnswer(inv -> {
                    inv.getArgument(0, Appointment.class).setId(103L);
                    return 1;
                })
                .when(appointmentMapper)
                .insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(61L, 0)).thenReturn(1);
        when(appointmentMapper.casStatus(101L, "RESERVED", "CANCELLED")).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(oldPool(7));
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(1);
        when(scheduleMapper.selectById(11L)).thenReturn(oldSchedule());

        AppointmentVO vo = service.reschedule("AP20260920000001", new RescheduleRequest(61L));

        // WINDOW 占位无支付时限语义（与预约主链一致）；零延迟信封（放旧删旧占位键照常承载）
        assertThat(vo.payDeadline()).isNull();
        verify(rabbitTemplate, never())
                .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("改期：rescheduled 链事件——oldApptNo/newApptNo/patientId/newSchedDate/newSlotStart 载荷断言")
    void reschedulePublishesLinkEvent() {
        stubRescheduleHappyPath();
        stubNewPoolDeductHeld();

        AppointmentVO vo = service.reschedule("AP20260920000001", new RescheduleRequest(61L));

        OutpatientDomainEvent event = capturedEvent(times(1));
        assertThat(event.eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_APPOINTMENT_RESCHEDULED);
        assertThat(event.payload()).isInstanceOf(AppointmentRescheduledPayload.class);
        AppointmentRescheduledPayload payload = (AppointmentRescheduledPayload) event.payload();
        assertThat(payload.oldApptNo()).isEqualTo("AP20260920000001");
        assertThat(payload.newApptNo()).isEqualTo(vo.apptNo());
        assertThat(payload.patientId()).isEqualTo(9L);
        assertThat(payload.newSchedDate()).isEqualTo(LocalDate.now().plusDays(5).format(SEQ_DATE));
        assertThat(payload.newSlotStart()).isEqualTo("09:00");
    }

    @Test
    @DisplayName("改期：新占 casOccupy 重试耗尽——OP-1003 且旧单零写（禁两头空的新占失败面），新池 Redis 持有回补")
    void rescheduleNewOccupyExhaustedKeepsOldUntouched() {
        Appointment old = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(old);
        // 重读始终 ACTIVE 且有余量（重试走满 3 次后判 OP-1003）
        when(apptNumberPoolMapper.selectById(61L)).thenReturn(newPool(PoolStatus.ACTIVE, 0));
        when(scheduleMapper.selectById(12L)).thenReturn(newSchedule());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(6L);
        when(poolRedisGate.deduct(eq(61L), eq(4L), any(Duration.class))).thenReturn(3);
        doAnswer(inv -> {
                    inv.getArgument(0, Appointment.class).setId(102L);
                    return 1;
                })
                .when(appointmentMapper)
                .insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(eq(61L), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> service.reschedule("AP20260920000001", new RescheduleRequest(61L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.POOL_EXHAUSTED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        // 首次+2 次重读重试=3 次 CAS；旧单零写（状态/回池均未触达）
        verify(apptNumberPoolMapper, times(3)).casOccupy(eq(61L), anyInt());
        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(apptNumberPoolMapper, never()).casRelease(anyLong(), anyInt());
        // 新池第一道闸持有回补（防占新失败漏补快路径余量）
        verify(poolRedisGate).release(eq(61L), eq(4L), any(Duration.class));
    }

    @Test
    @DisplayName("改期：旧单非 RESERVED（TAKEN）拒 OP-1009——改期仅承载未支付占位迁移（TAKEN 走退号链）")
    void rescheduleRejectsTakenAppointment() {
        Appointment taken = appointment(
                ApptStatus.TAKEN,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(taken);

        assertThatThrownBy(() -> service.reschedule("AP20260920000001", new RescheduleRequest(61L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(apptNumberPoolMapper, never()).selectById(61L);
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    @DisplayName("改期：旧单已支付（PAID）拒 OP-1009——资金无涉红线：改期不承载退费链，防已付旧单静默作废资金面")
    void rescheduleRejectsPaidAppointment() {
        Appointment paid = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.PAID,
                501L,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(paid);

        assertThatThrownBy(() -> service.reschedule("AP20260920000001", new RescheduleRequest(61L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(billingPort);
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    @DisplayName("改期：新池不存在拒 OP-1002 404——占新面零触达")
    void rescheduleRejectsWhenNewPoolMissing() {
        Appointment old = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(old);
        when(apptNumberPoolMapper.selectById(61L)).thenReturn(null);

        assertThatThrownBy(() -> service.reschedule("AP20260920000001", new RescheduleRequest(61L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.POOL_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(appointmentMapper, never()).insert(any(Appointment.class));
        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("改期：新池排班缺失（池行悬挂）拒 OP-1004——占新面零触达")
    void rescheduleRejectsWhenNewScheduleMissing() {
        Appointment old = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(old);
        when(apptNumberPoolMapper.selectById(61L)).thenReturn(newPool(PoolStatus.ACTIVE, 0));
        when(scheduleMapper.selectById(12L)).thenReturn(null);

        assertThatThrownBy(() -> service.reschedule("AP20260920000001", new RescheduleRequest(61L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    @DisplayName("改期：新池停诊（STOPPED）拒 OP-1004——占新面零触达")
    void rescheduleRejectsWhenNewPoolStopped() {
        Appointment old = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(old);
        when(apptNumberPoolMapper.selectById(61L)).thenReturn(newPool(PoolStatus.STOPPED, 0));
        when(scheduleMapper.selectById(12L)).thenReturn(newSchedule());

        assertThatThrownBy(() -> service.reschedule("AP20260920000001", new RescheduleRequest(61L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(poolRedisGate, never()).deduct(anyLong(), anyLong(), any(Duration.class));
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    @DisplayName("改期：新池余量谓词旁路（读回已约满）拒 OP-1003——Redis 预扣零触达")
    void rescheduleRejectsWhenNewPoolExhaustedAtRead() {
        Appointment old = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(old);
        when(apptNumberPoolMapper.selectById(61L)).thenReturn(newPool(PoolStatus.ACTIVE, 4));
        when(scheduleMapper.selectById(12L)).thenReturn(newSchedule());

        assertThatThrownBy(() -> service.reschedule("AP20260920000001", new RescheduleRequest(61L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.POOL_EXHAUSTED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(poolRedisGate, never()).deduct(anyLong(), anyLong(), any(Duration.class));
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    @DisplayName("改期：新单落库并发限购冲突（uk_appt_patient，同日同科目标）——回补新池持有后映射 OP-1005 整单回滚")
    void rescheduleDuplicateKeyMapsToOp1005() {
        Appointment old = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(old);
        when(apptNumberPoolMapper.selectById(61L)).thenReturn(newPool(PoolStatus.ACTIVE, 0));
        when(scheduleMapper.selectById(12L)).thenReturn(newSchedule());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(7L);
        when(poolRedisGate.deduct(eq(61L), eq(4L), any(Duration.class))).thenReturn(3);
        when(appointmentMapper.insert(any(Appointment.class))).thenThrow(new DuplicateKeyException("uk_appt_patient"));

        assertThatThrownBy(() -> service.reschedule("AP20260920000001", new RescheduleRequest(61L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.DUPLICATE_APPOINTMENT);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        // 败者侧第一道闸持有回补（与预约主链对称）
        verify(poolRedisGate).release(eq(61L), eq(4L), any(Duration.class));
        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("改期：Redis 预扣新池返回 -1（余量不足）判 OP-1003 直接拒绝不降级——新单零落库旧单零写")
    void rescheduleRejectsWhenRedisReportsExhausted() {
        Appointment old = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(old);
        when(apptNumberPoolMapper.selectById(61L)).thenReturn(newPool(PoolStatus.ACTIVE, 0));
        when(scheduleMapper.selectById(12L)).thenReturn(newSchedule());
        // 第一道闸余量不足（-1）：占新在预扣之后，新单号流水与新单落库均不触达
        when(poolRedisGate.deduct(eq(61L), eq(4L), any(Duration.class))).thenReturn(-1);

        assertThatThrownBy(() -> service.reschedule("AP20260920000001", new RescheduleRequest(61L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.POOL_EXHAUSTED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(appointmentMapper, never()).insert(any(Appointment.class));
        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(apptNumberPoolMapper, never()).casRelease(anyLong(), anyInt());
    }

    @Test
    @DisplayName("改期：占新成功后旧单 CAS 落败（并发已迁移）——fail-fast 整单回滚并回补新池持有+删新占位键")
    void rescheduleRejectsWhenOldCasLose() {
        Appointment old = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(old);
        when(apptNumberPoolMapper.selectById(61L)).thenReturn(newPool(PoolStatus.ACTIVE, 0));
        when(scheduleMapper.selectById(12L)).thenReturn(newSchedule());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(9L);
        when(poolRedisGate.deduct(eq(61L), eq(4L), any(Duration.class))).thenReturn(3);
        doAnswer(inv -> {
                    inv.getArgument(0, Appointment.class).setId(102L);
                    inv.getArgument(0, Appointment.class).setApptNo("AP20260921000009");
                    return 1;
                })
                .when(appointmentMapper)
                .insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(61L, 0)).thenReturn(1);
        when(appointmentMapper.casStatus(101L, "RESERVED", "CANCELLED")).thenReturn(0);

        assertThatThrownBy(() -> service.reschedule("AP20260920000001", new RescheduleRequest(61L)))
                .isInstanceOf(IllegalStateException.class);
        // 对称回补：新池 Redis 持有 + 新单占位键清理（事务回滚残留面收敛）
        verify(poolRedisGate).release(eq(61L), eq(4L), any(Duration.class));
        verify(redisTemplate).delete("fy:outpatient:pay-hold:AP20260921000009");
        verify(apptNumberPoolMapper, never()).casRelease(anyLong(), anyInt());
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("改期：占新成功后 PORTAL 占位登记失败（延迟信封入队异常）——对称回补新池持有+删新占位键后原样上抛整单回滚")
    void rescheduleRollsBackWhenHoldRegistrationFails() {
        Appointment old = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(old);
        when(apptNumberPoolMapper.selectById(61L)).thenReturn(newPool(PoolStatus.ACTIVE, 0));
        when(scheduleMapper.selectById(12L)).thenReturn(newSchedule());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(10L);
        when(poolRedisGate.deduct(eq(61L), eq(4L), any(Duration.class))).thenReturn(3);
        doAnswer(inv -> {
                    inv.getArgument(0, Appointment.class).setId(102L);
                    inv.getArgument(0, Appointment.class).setApptNo("AP20260921000010");
                    return 1;
                })
                .when(appointmentMapper)
                .insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(61L, 0)).thenReturn(1);
        doThrow(new AmqpException("broker 不可达"))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        assertThatThrownBy(() -> service.reschedule("AP20260920000001", new RescheduleRequest(61L)))
                .isInstanceOf(AmqpException.class);
        // 对称回补：新池 Redis 持有 + 新单占位键清理；旧单零写（事件交事务回滚不发布）
        verify(poolRedisGate).release(eq(61L), eq(4L), any(Duration.class));
        verify(redisTemplate).delete("fy:outpatient:pay-hold:AP20260921000010");
        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("改期：旧单 Redis 预扣流水缺失 fail-fast——新单零落库旧单零写")
    void rescheduleFailsFastWhenApptSeqMissing() {
        Appointment old = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(old);
        when(apptNumberPoolMapper.selectById(61L)).thenReturn(newPool(PoolStatus.ACTIVE, 0));
        when(scheduleMapper.selectById(12L)).thenReturn(newSchedule());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.reschedule("AP20260920000001", new RescheduleRequest(61L)))
                .isInstanceOf(IllegalStateException.class);
        verify(appointmentMapper, never()).insert(any(Appointment.class));
        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("改期：旧单不存在拒 OP-1009——占新面零触达")
    void rescheduleRejectsWhenAppointmentMissing() {
        when(appointmentMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.reschedule("AP20260920000099", new RescheduleRequest(61L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(apptNumberPoolMapper, never()).selectById(61L);
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    @DisplayName("改期：Redis 池键缺失（-2）降级直连 DB 条件更新——casOccupy 成功路径照常完成（功能不中断）")
    void rescheduleDegradesWhenRedisKeyMissing() {
        stubRescheduleHappyPath();
        when(poolRedisGate.deduct(eq(61L), eq(4L), any(Duration.class))).thenReturn(-2);

        AppointmentVO vo = service.reschedule("AP20260920000001", new RescheduleRequest(61L));

        // 行为断言：降级路径 casOccupy 成功、旧单放旧完成；降级无新池 Redis 持有（回补面仅旧池快路径）
        verify(apptNumberPoolMapper).casOccupy(61L, 0);
        verify(apptNumberPoolMapper).casRelease(31L, 7);
        verify(poolRedisGate, never()).release(eq(61L), anyLong(), any(Duration.class));
        assertThat(vo.status()).isEqualTo(ApptStatus.RESERVED);
    }

    @Test
    @DisplayName("改期：Redis 连接异常降级直连 DB 条件更新——主链不中断完成")
    void rescheduleDegradesWhenRedisDown() {
        stubRescheduleHappyPath();
        when(poolRedisGate.deduct(eq(61L), eq(4L), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        AppointmentVO vo = service.reschedule("AP20260920000001", new RescheduleRequest(61L));

        verify(apptNumberPoolMapper).casOccupy(61L, 0);
        verify(apptNumberPoolMapper).casRelease(31L, 7);
        verify(poolRedisGate, never()).release(eq(61L), anyLong(), any(Duration.class));
        assertThat(vo.status()).isEqualTo(ApptStatus.RESERVED);
    }

    // ---------------------------------------------------------------- 退号/改期入口守卫（覆盖率收口）

    @Test
    @DisplayName("cancel：预约单不存在拒 OP-1009——billing 与回池面零触达")
    void cancelRejectsWhenAppointmentMissing() {
        when(appointmentMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.cancel("AP20260920000099", "行程变动取消"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(billingPort);
        verify(apptNumberPoolMapper, never()).casRelease(anyLong(), anyInt());
    }

    @Test
    @DisplayName("cancel：终态单（CANCELLED/NO_SHOW）拒 OP-1009——状态不允许退号")
    void cancelRejectsWhenTerminalState() {
        Appointment cancelled = appointment(
                ApptStatus.CANCELLED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(cancelled);

        assertThatThrownBy(() -> service.cancel("AP20260920000001", "行程变动取消"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("cancel 分支 1：状态 CAS 并发落败（重复提交/已超时 NO_SHOW）——幂等跳过零回池零事件")
    void unpaidCancelIdempotentWhenCasLose() {
        Appointment unpaid = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.UNPAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(unpaid);
        when(appointmentMapper.casStatus(101L, "RESERVED", "CANCELLED")).thenReturn(0);
        when(appointmentMapper.selectById(101L)).thenReturn(unpaid);

        service.cancel("AP20260920000001", "行程变动取消");

        verify(apptNumberPoolMapper, never()).casRelease(anyLong(), anyInt());
        verify(poolRedisGate, never()).release(anyLong(), anyLong(), any(Duration.class));
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("cancel 分支 3：TAKEN 但 visit 缺失（数据异常）fail-fast——billing 零触达")
    void cancelRejectsWhenTakenVisitMissing() {
        Appointment taken = appointment(
                ApptStatus.TAKEN,
                FeeStatusType.PAID,
                501L,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(taken);
        when(visitMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.cancel("AP20260920000001", "行程变动取消"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(billingPort);
    }

    @Test
    @DisplayName("cancel：已支付但无结算锚（PAID+fee_settlement_id 缺失）按无结算可退走分支 1——零 billing 触达")
    void paidWithoutSettlementFallsToFreeCancel() {
        Appointment paidNoSettlement = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.PAID,
                null,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                null);
        when(appointmentMapper.selectOne(any())).thenReturn(paidNoSettlement);
        when(appointmentMapper.casStatus(101L, "RESERVED", "CANCELLED")).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(oldPool(7));
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(1);
        when(scheduleMapper.selectById(11L)).thenReturn(oldSchedule());

        service.cancel("AP20260920000001", "行程变动取消");

        verifyNoInteractions(billingPort);
        verify(apptNumberPoolMapper).casRelease(31L, 7);
        assertThat(capturedEvent(times(1)).eventType())
                .isEqualTo(OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED);
    }

    @Test
    @DisplayName("cancel：已支付但无可退费用行（结算锚不匹配）fail-fast——零退费申请零取消")
    void paidCancelFailsFastWhenNoRefundableFeeRows() {
        Appointment paid = appointment(
                ApptStatus.RESERVED,
                FeeStatusType.PAID,
                501L,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(paid);
        // 查得行归属他结算单（999≠501）→ 勾选为空 → 数据异常 fail-fast
        when(billingPort.feesByVisit("O20260921000001")).thenReturn(List.of(settledFee(1L, 999L)));

        assertThatThrownBy(() -> service.cancel("AP20260920000001", "行程变动取消"))
                .isInstanceOf(IllegalStateException.class);
        verify(billingPort, never()).applyRefund(any(VisitRefundCommand.class));
        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("回执：TAKEN 单 visit 缺失（数据异常）fail-fast——终态事件零发布（交事务回滚死信留痕）")
    void refundReceiptFailsFastWhenTakenVisitMissing() {
        Appointment taken = appointment(
                ApptStatus.TAKEN,
                FeeStatusType.PAID,
                501L,
                ApptChannel.PORTAL,
                LocalDate.now().plusDays(3),
                "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(taken);
        when(appointmentMapper.casStatus(101L, "TAKEN", "CANCELLED")).thenReturn(1);
        when(appointmentMapper.casMarkRefunded(101L)).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(oldPool(7));
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(1);
        when(scheduleMapper.selectById(11L)).thenReturn(oldSchedule());
        when(visitMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.confirmRefundedCancel(refundReceipt()))
                .isInstanceOf(IllegalStateException.class);
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    // ---------------------------------------------------------------- 爽约信用管理

    @Test
    @DisplayName("信用手工解除：restrict_to 提前至今日-1+release_reason 留痕（操作者上下文注入 updated_by）")
    void creditReleaseShortensRestriction() {
        when(apptCreditRecordMapper.selectById(77L))
                .thenReturn(credit(LocalDate.now().plusDays(90)));

        ApptCreditVO vo = service.releaseCredit(77L, "患者申诉核实通过");

        ArgumentCaptor<ApptCreditRecord> captor = ArgumentCaptor.forClass(ApptCreditRecord.class);
        verify(apptCreditRecordMapper).updateById(captor.capture());
        ApptCreditRecord updated = captor.getValue();
        assertThat(updated.getRestrictTo()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(updated.getReleaseReason()).isEqualTo("患者申诉核实通过");
        assertThat(updated.getUpdatedBy()).isEqualTo("admin001");
        assertThat(vo.restrictTo()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(vo.releaseReason()).isEqualTo("患者申诉核实通过");
    }

    @Test
    @DisplayName("信用手工解除：记录不存在拒 OP-1009（404）——零更新")
    void creditReleaseRejectsWhenRecordMissing() {
        when(apptCreditRecordMapper.selectById(78L)).thenReturn(null);

        assertThatThrownBy(() -> service.releaseCredit(78L, "患者申诉核实通过"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(apptCreditRecordMapper, never()).updateById(any(ApptCreditRecord.class));
    }

    @Test
    @DisplayName("信用手工解除：无限约区间（restrict_to 为空）拒 OP-1009——无可解除面")
    void creditReleaseRejectsWhenNoActiveRestriction() {
        when(apptCreditRecordMapper.selectById(77L)).thenReturn(credit(null));

        assertThatThrownBy(() -> service.releaseCredit(77L, "患者申诉核实通过"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(apptCreditRecordMapper, never()).updateById(any(ApptCreditRecord.class));
    }

    @Test
    @DisplayName("信用记录查询：按患者维度列表（id 降序最新在前）与 VO 投影映射")
    void creditsByPatientReturnsVos() {
        when(apptCreditRecordMapper.selectList(any()))
                .thenReturn(List.of(credit(LocalDate.now().plusDays(90))));

        List<ApptCreditVO> vos = service.creditsByPatient(9L);

        assertThat(vos).hasSize(1);
        assertThat(vos.get(0).id()).isEqualTo(77L);
        assertThat(vos.get(0).patientId()).isEqualTo(9L);
        assertThat(vos.get(0).action()).isEqualTo("NO_SHOW");
        assertThat(vos.get(0).restrictFrom()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(vos.get(0).restrictTo()).isEqualTo(LocalDate.now().plusDays(90));
    }
}
