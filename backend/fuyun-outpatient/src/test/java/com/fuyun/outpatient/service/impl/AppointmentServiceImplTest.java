package com.fuyun.outpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
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
import com.fuyun.outpatient.properties.OutpatientProperties;
import com.fuyun.outpatient.service.IVisitIdIssuer;
import com.fuyun.outpatient.vo.AppointmentVO;
import com.fuyun.outpatient.vo.VisitVO;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import com.fuyun.patient.api.VisitIdValidator;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
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
 * 预约/当日挂号服务单测（M03 FU-M03-02/03，Task 5 冻结用例集 14 例）：窗口一步 TAKEN、portal
 * 15 分钟占位+延迟信封入队、双道闸（Redis 预扣 -1 拒绝不降级/异常降级 DB CAS/casOccupy 耗尽回补）、
 * 限购与爽约限约与冻结拦截、取号支付时限守卫、超时 NO_SHOW 幂等与 version 条件回池竞态守卫。
 * AFTER_COMMIT 的 MQ 出线时机归 OutpatientEventPublisherTest 与集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class AppointmentServiceImplTest {

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
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<OutpatientDomainEvent> eventCaptor;

    private DelayEnvelopeSender delayEnvelopeSender;

    private AppointmentServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 预约单号定位/限购计数/信用窗口/就诊号定位的 lambda 条件列解析依赖 TableInfo（容器外单测手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Appointment.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Visit.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApptCreditRecord.class);
    }

    @BeforeEach
    void setUp() {
        // 缺省参数（与 V204/V705 与 Properties @DefaultValue 同源）：支付时限 15m、爽约窗口 90 天、阈值 3、限约 90 天
        OutpatientProperties properties = new OutpatientProperties(Duration.ofMinutes(15), 1, 90, 3, 90);
        delayEnvelopeSender = new DelayEnvelopeSender(rabbitTemplate, new EventEnvelopeCodec(new ObjectMapper()));
        service = new AppointmentServiceImpl(
                patientContextResolver,
                visitIdIssuer,
                apptNumberPoolMapper,
                scheduleMapper,
                appointmentMapper,
                visitMapper,
                apptCreditRecordMapper,
                poolRedisGate,
                redisTemplate,
                delayEnvelopeSender,
                events,
                properties);
        OperatorContextHolder.set("admin001");
    }

    @AfterEach
    void tearDown() {
        OperatorContextHolder.clear();
    }

    // ---------------------------------------------------------------- 替身构造

    /** 正常患者解析视图（归一主档=9，未拦截） */
    private PatientContextView normalPatient() {
        return new PatientContextView(9L, 9L, "NORMAL", false, "");
    }

    /** ACTIVE 号源池行替身（total=4、used=1、version=0） */
    private ApptNumberPool activePool(PoolStatus status) {
        ApptNumberPool pool = new ApptNumberPool();
        pool.setId(31L);
        pool.setScheduleId(11L);
        pool.setApptType(ApptType.EXPERT);
        pool.setSlotStart(LocalTime.of(8, 0));
        pool.setSlotEnd(LocalTime.of(8, 30));
        pool.setTotalQuota(4);
        pool.setUsedCount(1);
        pool.setVersion(0);
        pool.setStatus(status);
        return pool;
    }

    /** 排班日历替身（池行 31 所属：3 日后上午、DEP001/DOC001） */
    private Schedule schedule() {
        Schedule schedule = new Schedule();
        schedule.setId(11L);
        schedule.setTemplateId(1L);
        schedule.setSchedDate(LocalDate.now().plusDays(3));
        schedule.setSession(SessionType.MORNING);
        schedule.setDeptCode("DEP001");
        schedule.setDoctorId("DOC001");
        schedule.setApptType(ApptType.EXPERT);
        schedule.setTotalQuota(4);
        schedule.setUsedQuota(0);
        schedule.setStatus(ScheduleStatus.NORMAL);
        return schedule;
    }

    /** 爽约信用行替身（action=NO_SHOW，restrictTo 由入参定） */
    private ApptCreditRecord noShowCredit(LocalDate restrictTo) {
        ApptCreditRecord record = new ApptCreditRecord();
        record.setPatientId(9L);
        record.setAction("NO_SHOW");
        record.setOccurredAt(OffsetDateTime.now().minusDays(1));
        record.setWindowDays(90);
        record.setRestrictFrom(LocalDate.now().minusDays(1));
        record.setRestrictTo(restrictTo);
        return record;
    }

    /** 预约落库替身回填雪花 id（doAnswer 消费 InvocationOnMock，返回影响行数 1） */
    private Object stubInsertId(InvocationOnMock inv) {
        inv.getArgument(0, Appointment.class).setId(101L);
        return 1;
    }

    /** 预约单替身（status/visit_id/pay_deadline 由入参定，供取号与超时分支构造） */
    private Appointment reservedAppointment(ApptStatus status, OffsetDateTime payDeadline, String visitId) {
        Appointment appointment = new Appointment();
        appointment.setId(101L);
        appointment.setApptNo("AP20260920000001");
        appointment.setPatientId(9L);
        appointment.setScheduleId(11L);
        appointment.setPoolId(31L);
        appointment.setDeptCode("DEP001");
        appointment.setApptType(ApptType.EXPERT);
        appointment.setSchedDate(LocalDate.now().plusDays(3));
        appointment.setSlotStart(LocalTime.of(8, 0));
        appointment.setSlotEnd(LocalTime.of(8, 30));
        appointment.setChannel(ApptChannel.PORTAL);
        appointment.setFeeStatus(FeeStatusType.UNPAID);
        appointment.setPayDeadline(payDeadline);
        appointment.setVisitId(visitId);
        appointment.setStatus(status);
        return appointment;
    }

    /** 预约请求替身 */
    private AppointmentCreateRequest request(String channel) {
        return new AppointmentCreateRequest(9L, 31L, channel);
    }

    // ---------------------------------------------------------------- 预约主流程

    @Test
    @DisplayName("book：WINDOW 渠道当日挂号——appointment TAKEN+visit REGISTERED+visit_id O 型形态+visit.registered 发布（五组件）")
    void windowRegistrationIssuesVisitAndMarksTaken() {
        String today = LocalDate.now().format(SEQ_DATE);
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:outpatient:appt-seq:" + today)).thenReturn(1L);
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(3);
        doAnswer(this::stubInsertId).when(appointmentMapper).insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(31L, 0)).thenReturn(1);
        String visitId = "O" + today + "00001";
        when(visitIdIssuer.issue()).thenReturn(visitId);
        when(appointmentMapper.casTake(101L, visitId)).thenReturn(1);
        doAnswer(inv -> {
                    inv.getArgument(0, Visit.class).setId(501L);
                    return 1;
                })
                .when(visitMapper)
                .insert(any(Visit.class));

        AppointmentVO vo = service.book(request("WINDOW"));

        // appointment 落库断言：渠道/费态/支付时限/冗余科室与操作者
        ArgumentCaptor<Appointment> apptCaptor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentMapper).insert(apptCaptor.capture());
        assertThat(apptCaptor.getValue().getApptNo()).isEqualTo("AP" + today + "000001");
        assertThat(apptCaptor.getValue().getChannel()).isEqualTo(ApptChannel.WINDOW);
        assertThat(apptCaptor.getValue().getFeeStatus()).isEqualTo(FeeStatusType.UNPAID);
        assertThat(apptCaptor.getValue().getPayDeadline()).isNull();
        assertThat(apptCaptor.getValue().getDeptCode()).isEqualTo("DEP001");
        assertThat(apptCaptor.getValue().getSchedDate())
                .isEqualTo(LocalDate.now().plusDays(3));
        assertThat(apptCaptor.getValue().getCreatedBy()).isEqualTo("admin001");
        // visit 落库断言：CF-3 冻结形态 + 初始 REGISTERED
        ArgumentCaptor<Visit> visitCaptor = ArgumentCaptor.forClass(Visit.class);
        verify(visitMapper).insert(visitCaptor.capture());
        assertThat(visitCaptor.getValue().getVisitId()).isEqualTo(visitId);
        assertThat(VisitIdValidator.isValid(visitId)).isTrue();
        assertThat(visitCaptor.getValue().getPatientId()).isEqualTo(9L);
        assertThat(visitCaptor.getValue().getApptId()).isEqualTo(101L);
        assertThat(visitCaptor.getValue().getDeptCode()).isEqualTo("DEP001");
        assertThat(visitCaptor.getValue().getDoctorId()).isEqualTo("DOC001");
        assertThat(visitCaptor.getValue().getStatus()).isEqualTo(VisitStatus.REGISTERED);
        // casTake 断言：appointment RESERVED→TAKEN + visit_id 回填
        verify(appointmentMapper).casTake(101L, visitId);
        // 事件断言：eventType + 五组件（visitId/patientId/visitType/deptCode/doctorId）
        verify(events).publishEvent(eventCaptor.capture());
        OutpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_VISIT_REGISTERED);
        assertThat(event.payload()).isInstanceOf(VisitRegisteredPayload.class);
        VisitRegisteredPayload payload = (VisitRegisteredPayload) event.payload();
        assertThat(payload.visitId()).isEqualTo(visitId);
        assertThat(payload.patientId()).isEqualTo(9L);
        assertThat(payload.visitType()).isEqualTo("GENERAL");
        assertThat(payload.deptCode()).isEqualTo("DEP001");
        assertThat(payload.doctorId()).isEqualTo("DOC001");
        // VO 断言：一步直达 TAKEN + visit_id 回显
        assertThat(vo.id()).isEqualTo(101L);
        assertThat(vo.apptNo()).isEqualTo("AP" + today + "000001");
        assertThat(vo.status()).isEqualTo(ApptStatus.TAKEN);
        assertThat(vo.visitId()).isEqualTo(visitId);
        // 窗口渠道零延迟面：无占位键、无延迟信封、无 booked 事件
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        verify(rabbitTemplate, never())
                .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
        verify(events, times(1)).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("book：PORTAL 渠道预约——RESERVED+pay_deadline≈now+15m+占位键+延迟信封入队（routing key）+appointment.booked 发布")
    void portalBookingHoldsSlotWithPayDeadline() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(1L);
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(3);
        doAnswer(this::stubInsertId).when(appointmentMapper).insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(31L, 0)).thenReturn(1);

        AppointmentVO vo = service.book(request("PORTAL"));

        // 落库断言：RESERVED + 支付时限 now+15m（±2s）+ portal 哨兵操作者
        ArgumentCaptor<Appointment> apptCaptor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentMapper).insert(apptCaptor.capture());
        assertThat(apptCaptor.getValue().getStatus()).isEqualTo(ApptStatus.RESERVED);
        assertThat(apptCaptor.getValue().getPayDeadline())
                .isCloseTo(OffsetDateTime.now().plusMinutes(15), within(2, ChronoUnit.SECONDS));
        assertThat(apptCaptor.getValue().getChannel()).isEqualTo(ApptChannel.PORTAL);
        assertThat(apptCaptor.getValue().getCreatedBy()).isEqualTo("PORTAL");
        assertThat(vo.status()).isEqualTo(ApptStatus.RESERVED);
        assertThat(vo.visitId()).isNull();
        // 占位键写入断言：fy:outpatient:pay-hold:{apptNo}，TTL=支付时限+5min 缓冲（±60s）
        ArgumentCaptor<Duration> holdTtlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations)
                .set(eq("fy:outpatient:pay-hold:" + vo.apptNo()), eq(vo.apptNo()), holdTtlCaptor.capture());
        Duration expectedHoldTtl = Duration.ofMinutes(20);
        assertThat(Math.abs(holdTtlCaptor.getValue().minus(expectedHoldTtl).toSeconds()))
                .isLessThan(60);
        // 延迟信封入队断言：fy.delay / delay.appointment-timeout / 载荷三组件
        ArgumentCaptor<EventEnvelope> envelopeCaptor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(rabbitTemplate)
                .convertAndSend(
                        eq("fy.delay"),
                        eq("delay.appointment-timeout"),
                        envelopeCaptor.capture(),
                        any(CorrelationData.class));
        EventEnvelope envelope = envelopeCaptor.getValue();
        assertThat(envelope.eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_APPOINTMENT_TIMEOUT);
        assertThat(envelope.payload().path("apptNo").asText()).isEqualTo(vo.apptNo());
        assertThat(envelope.payload().path("patientId").asLong()).isEqualTo(9L);
        assertThat(envelope.payload().path("poolId").asLong()).isEqualTo(31L);
        // booked 事件断言（七组件中的锚点值）
        verify(events).publishEvent(eventCaptor.capture());
        OutpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_APPOINTMENT_BOOKED);
        assertThat(event.payload()).isInstanceOf(AppointmentBookedPayload.class);
        AppointmentBookedPayload booked = (AppointmentBookedPayload) event.payload();
        assertThat(booked.apptNo()).isEqualTo(vo.apptNo());
        assertThat(booked.patientId()).isEqualTo(9L);
        assertThat(booked.schedDate()).isEqualTo(LocalDate.now().plusDays(3).format(SEQ_DATE));
        assertThat(booked.session()).isEqualTo("MORNING");
        assertThat(booked.deptCode()).isEqualTo("DEP001");
        assertThat(booked.apptType()).isEqualTo("EXPERT");
        assertThat(booked.channel()).isEqualTo("PORTAL");
        // 预约占位零 visit 面
        verify(visitMapper, never()).insert(any(Visit.class));
        verify(appointmentMapper, never()).casTake(anyLong(), anyString());
    }

    @Test
    @DisplayName("book：池行已停诊（STOPPED）拒 OP-1004——零扣减零落库")
    void bookingRejectsWhenPoolStopped() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.STOPPED));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(appointmentMapper, never()).insert(any(Appointment.class));
        verify(poolRedisGate, never()).deduct(anyLong(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("book：Redis 预扣返回 -1（余量不足）判 OP-1003 直接拒绝不降级——零 CAS 零落库")
    void bookingRejectsWhenRedisReportsExhausted() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(-1);

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.POOL_EXHAUSTED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(appointmentMapper, never()).insert(any(Appointment.class));
        verify(apptNumberPoolMapper, never()).casOccupy(anyLong(), anyInt());
    }

    @Test
    @DisplayName("book：Redis 连接异常降级直连 DB 条件更新——casOccupy 成功路径照常落库（功能不中断）")
    void bookingDegradesToDbCasWhenRedisDown() {
        String today = LocalDate.now().format(SEQ_DATE);
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:outpatient:appt-seq:" + today)).thenReturn(2L);
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        doAnswer(this::stubInsertId).when(appointmentMapper).insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(31L, 0)).thenReturn(1);
        String visitId = "O" + today + "00002";
        when(visitIdIssuer.issue()).thenReturn(visitId);
        when(appointmentMapper.casTake(101L, visitId)).thenReturn(1);
        when(visitMapper.insert(any(Visit.class))).thenReturn(1);

        AppointmentVO vo = service.book(request("WINDOW"));

        // 行为断言：降级路径 casOccupy 成功、appointment 落库 1 次、全链完成 TAKEN
        verify(appointmentMapper, times(1)).insert(any(Appointment.class));
        verify(apptNumberPoolMapper).casOccupy(31L, 0);
        assertThat(vo.status()).isEqualTo(ApptStatus.TAKEN);
        // 降级路径无 Redis 持有，失败回补面零触达
        verify(poolRedisGate, never()).release(anyLong(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("book：casOccupy 重读重试 ≤2 次仍 0 行——OP-1003 并回补 Redis 持有（release）")
    void bookingRollsBackRedisHoldWhenDbCasExhausted() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(1L);
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(3);
        when(apptNumberPoolMapper.casOccupy(eq(31L), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.POOL_EXHAUSTED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        // 首次 + 2 次重读重试 = 3 次 CAS；每次重试前重读池行
        verify(apptNumberPoolMapper, times(3)).casOccupy(eq(31L), anyInt());
        // ⑥ 序锁死：appointment 先落库后 CAS（CAS 耗尽由 @Transactional 整单回滚，单测仅锚一次写入）
        verify(appointmentMapper, times(1)).insert(any(Appointment.class));
        // Redis 持有回补断言（防第一道闸漏扣）
        verify(poolRedisGate).release(eq(31L), eq(4L), any(Duration.class));
    }

    @Test
    @DisplayName("book：同患者同日同科已有 RESERVED/TAKEN（uk_appt_patient）拒 OP-1005——零扣减零落库")
    void bookingRejectsDuplicateSameDaySameDept() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.book(request("PORTAL"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.DUPLICATE_APPOINTMENT);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(appointmentMapper, never()).insert(any(Appointment.class));
        verify(poolRedisGate, never()).deduct(anyLong(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("book：爽约窗口内 3 次 NO_SHOW 且 restrict_to≥今日拒 OP-1006——零扣减零落库")
    void bookingRejectsRestrictedNoShowPatient() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any()))
                .thenReturn(List.of(
                        noShowCredit(LocalDate.now().plusDays(10)),
                        noShowCredit(LocalDate.now().plusDays(10)),
                        noShowCredit(LocalDate.now().plusDays(10))));

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.APPT_RESTRICTED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(appointmentMapper, never()).insert(any(Appointment.class));
        verify(poolRedisGate, never()).deduct(anyLong(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("book：resolver 冻结拦截（blocked=true）拒 OP-1007——后续读取面零触达")
    void bookingRejectsFrozenPatient() {
        when(patientContextResolver.resolve(9L)).thenReturn(new PatientContextView(9L, 9L, "FROZEN", true, "医保欠费冻结"));

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PATIENT_BLOCKED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(apptNumberPoolMapper, never()).selectById(anyLong());
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    // ---------------------------------------------------------------- 预约取号

    @Test
    @DisplayName("take：支付时限内 casTake 1 行——visit 签发+占位键删除+visit.registered 发布")
    void takeIssuesVisitWithinPayDeadline() {
        String visitId = "O" + LocalDate.now().format(SEQ_DATE) + "00002";
        Appointment held =
                reservedAppointment(ApptStatus.RESERVED, OffsetDateTime.now().plusMinutes(5), null);
        when(appointmentMapper.selectOne(any())).thenReturn(held);
        when(visitIdIssuer.issue()).thenReturn(visitId);
        when(appointmentMapper.casTake(101L, visitId)).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(visitMapper.insert(any(Visit.class))).thenReturn(1);

        VisitVO vo = service.take("AP20260920000001");

        verify(appointmentMapper).casTake(101L, visitId);
        ArgumentCaptor<Visit> visitCaptor = ArgumentCaptor.forClass(Visit.class);
        verify(visitMapper).insert(visitCaptor.capture());
        assertThat(visitCaptor.getValue().getVisitId()).isEqualTo(visitId);
        assertThat(visitCaptor.getValue().getPatientId()).isEqualTo(9L);
        assertThat(visitCaptor.getValue().getApptId()).isEqualTo(101L);
        assertThat(visitCaptor.getValue().getStatus()).isEqualTo(VisitStatus.REGISTERED);
        // 占位键删除断言
        verify(redisTemplate).delete("fy:outpatient:pay-hold:AP20260920000001");
        // visit.registered 发布断言
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_VISIT_REGISTERED);
        assertThat(vo.visitId()).isEqualTo(visitId);
        assertThat(vo.status()).isEqualTo(VisitStatus.REGISTERED);
    }

    @Test
    @DisplayName("take：casTake 0 行且库态 RESERVED（pay_deadline 已过）拒 OP-1008——visit 零签发")
    void takeRejectsWhenDeadlinePassed() {
        Appointment expired =
                reservedAppointment(ApptStatus.RESERVED, OffsetDateTime.now().minusMinutes(1), null);
        when(appointmentMapper.selectOne(any())).thenReturn(expired);
        when(visitIdIssuer.issue()).thenReturn("O" + LocalDate.now().format(SEQ_DATE) + "00009");
        when(appointmentMapper.casTake(eq(101L), anyString())).thenReturn(0);
        when(appointmentMapper.selectById(101L)).thenReturn(expired);

        assertThatThrownBy(() -> service.take("AP20260920000001")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PAY_DEADLINE_PASSED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(visitMapper, never()).insert(any(Visit.class));
        verify(redisTemplate, never()).delete(anyString());
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    // ---------------------------------------------------------------- 支付超时释放

    @Test
    @DisplayName("markTimeout：casStatus RESERVED→NO_SHOW 1 行——version 条件回池+占位键删+credit 行（命中阈值写限约区间）")
    void timeoutMarksNoShowReleasesPoolAndRecordsCredit() {
        Appointment held =
                reservedAppointment(ApptStatus.RESERVED, OffsetDateTime.now().minusMinutes(20), null);
        when(appointmentMapper.selectOne(any())).thenReturn(held);
        when(appointmentMapper.casStatus(101L, "RESERVED", "NO_SHOW")).thenReturn(1);
        ApptNumberPool pool = activePool(PoolStatus.ACTIVE);
        pool.setVersion(7);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(pool);
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(1);
        when(apptCreditRecordMapper.selectCount(any())).thenReturn(2L);

        service.markTimeout(new AppointmentTimeoutPayload("AP20260920000001", 9L, 31L));

        // 回池断言：version 谓词条件更新命中 + Redis 回补 + 占位键删除
        verify(apptNumberPoolMapper).casRelease(31L, 7);
        verify(poolRedisGate).release(eq(31L), eq(4L), any(Duration.class));
        verify(redisTemplate).delete("fy:outpatient:pay-hold:AP20260920000001");
        // credit 行断言：action=NO_SHOW、窗口/阈值取 properties、既有 2 次+本次 1 次命中阈值 3 → 写限约区间
        ArgumentCaptor<ApptCreditRecord> creditCaptor = ArgumentCaptor.forClass(ApptCreditRecord.class);
        verify(apptCreditRecordMapper).insert(creditCaptor.capture());
        ApptCreditRecord credit = creditCaptor.getValue();
        assertThat(credit.getPatientId()).isEqualTo(9L);
        assertThat(credit.getAction()).isEqualTo("NO_SHOW");
        assertThat(credit.getWindowDays()).isEqualTo(90);
        assertThat(credit.getRestrictFrom()).isEqualTo(LocalDate.now());
        assertThat(credit.getRestrictTo()).isEqualTo(LocalDate.now().plusDays(90));
    }

    @Test
    @DisplayName("markTimeout：casStatus 0 行且库态 TAKEN——幂等 ACK 跳过，零回池零 credit 零占位键删除")
    void timeoutIdempotentWhenAlreadyTaken() {
        Appointment taken =
                reservedAppointment(ApptStatus.TAKEN, OffsetDateTime.now().minusMinutes(20), "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(taken);
        when(appointmentMapper.casStatus(101L, "RESERVED", "NO_SHOW")).thenReturn(0);
        when(appointmentMapper.selectById(101L)).thenReturn(taken);

        service.markTimeout(new AppointmentTimeoutPayload("AP20260920000001", 9L, 31L));

        verify(apptNumberPoolMapper, never()).casRelease(anyLong(), anyInt());
        verify(poolRedisGate, never()).release(anyLong(), anyLong(), any(Duration.class));
        verify(apptCreditRecordMapper, never()).insert(any(ApptCreditRecord.class));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("markTimeout：同一预约第二条超时消息（已 NO_SHOW）——业务态幂等守卫直接跳过，零二次释放零二次 credit")
    void timeoutRepeatDeliverySkipsAfterReleaseWithoutDoubleRelease() {
        Appointment released =
                reservedAppointment(ApptStatus.NO_SHOW, OffsetDateTime.now().minusMinutes(20), null);
        when(appointmentMapper.selectOne(any())).thenReturn(released);
        when(appointmentMapper.casStatus(101L, "RESERVED", "NO_SHOW")).thenReturn(0);
        when(appointmentMapper.selectById(101L)).thenReturn(released);

        service.markTimeout(new AppointmentTimeoutPayload("AP20260920000001", 9L, 31L));

        verify(apptNumberPoolMapper, never()).casRelease(anyLong(), anyInt());
        verify(poolRedisGate, never()).release(anyLong(), anyLong(), any(Duration.class));
        verify(apptCreditRecordMapper, never()).insert(any(ApptCreditRecord.class));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("markTimeout：并发释放竞态——casRelease version 谓词影响 0 行（并发已回池）不重复回补 Redis")
    void poolReleaseRaceGuardedByVersionConditionalUpdate() {
        Appointment held =
                reservedAppointment(ApptStatus.RESERVED, OffsetDateTime.now().minusMinutes(20), null);
        when(appointmentMapper.selectOne(any())).thenReturn(held);
        when(appointmentMapper.casStatus(101L, "RESERVED", "NO_SHOW")).thenReturn(1);
        ApptNumberPool pool = activePool(PoolStatus.ACTIVE);
        pool.setVersion(7);
        // 首读取得 version=7；并发消费者已回池使 version 前移——条件更新 0 行，重读定性
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(pool);
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(0);
        when(apptCreditRecordMapper.selectCount(any())).thenReturn(0L);

        service.markTimeout(new AppointmentTimeoutPayload("AP20260920000001", 9L, 31L));

        // 影响行数 0 判定：终止本轮回池——零 Redis 回补（禁重复回补/负余量）；credit 行照常落（患者爽约事实）
        verify(apptNumberPoolMapper).casRelease(31L, 7);
        verify(poolRedisGate, never()).release(anyLong(), anyLong(), any(Duration.class));
        ArgumentCaptor<ApptCreditRecord> creditCaptor = ArgumentCaptor.forClass(ApptCreditRecord.class);
        verify(apptCreditRecordMapper, times(1)).insert(creditCaptor.capture());
        assertThat(creditCaptor.getValue().getRestrictFrom()).isNull();
    }

    // ---------------------------------------------------------------- R1 修复环（Important-1 泄漏 + Important-2 分支覆盖）

    @Test
    @DisplayName("R1 book：并发限购 DuplicateKey 路径回补 Redis 持有后再抛 OP-1005（防快路径槽位泄漏）")
    void bookingReleasesRedisHoldWhenConcurrentDuplicateKey() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(1L);
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(3);
        when(appointmentMapper.insert(any(Appointment.class)))
                .thenThrow(new DuplicateKeyException("uk_appt_patient 冲突"));

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.DUPLICATE_APPOINTMENT);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        // 泄漏修复断言：事务回滚前 Redis 持有已回补（败者侧第一道闸余量恢复）
        verify(poolRedisGate).release(eq(31L), eq(4L), any(Duration.class));
        verify(apptNumberPoolMapper, never()).casOccupy(anyLong(), anyInt());
    }

    @Test
    @DisplayName("R1 book：portal 延迟信封入队失败（AmqpException）——回补持有+删占位键后原样上抛整单回滚")
    void bookingReleasesRedisHoldWhenDelayEnvelopeRejected() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(1L);
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(3);
        doAnswer(this::stubInsertId).when(appointmentMapper).insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(31L, 0)).thenReturn(1);
        doThrow(new AmqpException("broker 不可达"))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        assertThatThrownBy(() -> service.book(request("PORTAL"))).isInstanceOf(AmqpException.class);
        // 泄漏修复断言：占位登记失败路径 Redis 持有回补+占位键清理，booked 事件不发布（交事务回滚）
        verify(poolRedisGate).release(eq(31L), eq(4L), any(Duration.class));
        verify(redisTemplate).delete(startsWith("fy:outpatient:pay-hold:"));
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("R1 book：portal 载荷组装 fail-fast（排班缺失）——同款回补持有后上抛（对称面闭合）")
    void bookingReleasesHoldWhenPayloadAssemblyFails() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        // 首读（主流程校验）命中排班；scheduleOf 组装 booked 载荷重读时缺失——fail-fast 对称面
        when(scheduleMapper.selectById(11L)).thenReturn(schedule()).thenReturn(null);
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(1L);
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(3);
        doAnswer(this::stubInsertId).when(appointmentMapper).insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(31L, 0)).thenReturn(1);

        assertThatThrownBy(() -> service.book(request("PORTAL"))).isInstanceOf(IllegalStateException.class);
        verify(poolRedisGate).release(eq(31L), eq(4L), any(Duration.class));
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("R1 book：渠道词表外（FAX）拒 OP-1019——患者解析零触达")
    void bookRejectsUnknownChannelVocabulary() {
        assertThatThrownBy(() -> service.book(request("FAX"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
        verify(patientContextResolver, never()).resolve(anyLong());
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    @DisplayName("R1 book：P1 未开放渠道（MINIAPP 预留位）拒 OP-1019——患者解析零触达")
    void bookRejectsReservedChannelNotOpenInP1() {
        assertThatThrownBy(() -> service.book(request("MINIAPP"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
        verify(patientContextResolver, never()).resolve(anyLong());
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    @DisplayName("R1 take：预约单不存在拒 OP-1009——visit 零签发")
    void takeRejectsWhenAppointmentMissing() {
        when(appointmentMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.take("AP20260920000099")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(visitIdIssuer, never()).issue();
        verify(visitMapper, never()).insert(any(Visit.class));
    }

    @Test
    @DisplayName("R1 take：TAKEN 态重复取号幂等返回既有 visit——零重复签发零 CAS")
    void takeIdempotentReturnsExistingVisitWhenTaken() {
        String visitId = "O" + LocalDate.now().format(SEQ_DATE) + "00001";
        Appointment taken =
                reservedAppointment(ApptStatus.TAKEN, OffsetDateTime.now().minusMinutes(20), visitId);
        when(appointmentMapper.selectOne(any())).thenReturn(taken);
        Visit existing = new Visit();
        existing.setId(501L);
        existing.setVisitId(visitId);
        existing.setPatientId(9L);
        existing.setApptId(101L);
        existing.setDeptCode("DEP001");
        existing.setVisitType(VisitType.GENERAL);
        existing.setIsRevisit((short) 0);
        existing.setStatus(VisitStatus.REGISTERED);
        when(visitMapper.selectOne(any())).thenReturn(existing);

        VisitVO vo = service.take("AP20260920000001");

        assertThat(vo.visitId()).isEqualTo(visitId);
        assertThat(vo.status()).isEqualTo(VisitStatus.REGISTERED);
        verify(appointmentMapper, never()).casTake(anyLong(), anyString());
        verify(visitMapper, never()).insert(any(Visit.class));
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("R1 take：TAKEN 态但 visit 记录缺失（数据异常）拒 OP-1009")
    void takeRejectsWhenTakenButVisitMissing() {
        Appointment taken =
                reservedAppointment(ApptStatus.TAKEN, OffsetDateTime.now().minusMinutes(20), "O20260921000001");
        when(appointmentMapper.selectOne(any())).thenReturn(taken);
        when(visitMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.take("AP20260920000001")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(visitIdIssuer, never()).issue();
    }

    @Test
    @DisplayName("R1 take：casTake 0 行且库态 CANCELLED（非时限成因）拒 OP-1009")
    void takeRejectsWhenStateNotAllowedAfterCasLose() {
        Appointment cancelled =
                reservedAppointment(ApptStatus.CANCELLED, OffsetDateTime.now().minusMinutes(20), null);
        when(appointmentMapper.selectOne(any())).thenReturn(cancelled);
        when(visitIdIssuer.issue()).thenReturn("O" + LocalDate.now().format(SEQ_DATE) + "00009");
        when(appointmentMapper.casTake(eq(101L), anyString())).thenReturn(0);
        when(appointmentMapper.selectById(101L)).thenReturn(cancelled);

        assertThatThrownBy(() -> service.take("AP20260920000001")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.APPOINTMENT_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(visitMapper, never()).insert(any(Visit.class));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("R1 book：当日挂号 casTake 落败（新建行状态异常）fail-fast——visit 零落库（降级路径无持有不回补）")
    void windowCasTakeLoseFailsFastWithoutVisitInsert() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(1L);
        // 键缺失降级（-2）：无 Redis 持有，失败路径零回补面
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(-2);
        doAnswer(this::stubInsertId).when(appointmentMapper).insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(31L, 0)).thenReturn(1);
        when(visitIdIssuer.issue()).thenReturn("O" + LocalDate.now().format(SEQ_DATE) + "00007");
        when(appointmentMapper.casTake(101L, "O" + LocalDate.now().format(SEQ_DATE) + "00007"))
                .thenReturn(0);

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOf(IllegalStateException.class);
        verify(visitMapper, never()).insert(any(Visit.class));
        verify(poolRedisGate, never()).release(anyLong(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("R1 book：portal 占位键写入失败（Redis 异常）不阻断预约——延迟信封照常入队、零回补")
    void portalBookingToleratesHoldKeyWriteFailure() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(1L);
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(3);
        doAnswer(this::stubInsertId).when(appointmentMapper).insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(31L, 0)).thenReturn(1);

        AppointmentVO vo = service.book(request("PORTAL"));

        // 辅助标记失败不阻断主链：booked 事件发布（占位守卫以延迟档位为权威）
        assertThat(vo.status()).isEqualTo(ApptStatus.RESERVED);
        verify(rabbitTemplate)
                .convertAndSend(
                        eq("fy.delay"), eq("delay.appointment-timeout"), any(Object.class), any(CorrelationData.class));
        verify(poolRedisGate, never()).release(anyLong(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("R1 book：预约单号流水缺失（Redis 返回空）fail-fast IllegalStateException")
    void bookingFailsFastWhenApptSeqMissing() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(null);
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(-2);

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOf(IllegalStateException.class);
        verify(appointmentMapper, never()).insert(any(Appointment.class));
        verify(poolRedisGate, never()).release(anyLong(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("R1 book：CAS 重读发现池行停诊（漂移）——回补持有后拒 OP-1004 整单回滚")
    void bookingAbortsWhenPoolStoppedMidflight() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L))
                .thenReturn(activePool(PoolStatus.ACTIVE))
                .thenReturn(activePool(PoolStatus.STOPPED));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(1L);
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(3);
        doAnswer(this::stubInsertId).when(appointmentMapper).insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(31L, 0)).thenReturn(0);

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        // 首次 CAS 即重读出停诊：仅 1 次 CAS，持有已回补
        verify(apptNumberPoolMapper, times(1)).casOccupy(eq(31L), anyInt());
        verify(poolRedisGate).release(eq(31L), eq(4L), any(Duration.class));
    }

    @Test
    @DisplayName("R1 book：CAS 重读发现余量耗尽——提前终止重试判 OP-1003（不再空转 CAS）并回补持有")
    void bookingStopsRetryWhenRereadSeesPoolExhausted() {
        ApptNumberPool exhausted = activePool(PoolStatus.ACTIVE);
        exhausted.setUsedCount(4);
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L))
                .thenReturn(activePool(PoolStatus.ACTIVE))
                .thenReturn(exhausted);
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(1L);
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(3);
        doAnswer(this::stubInsertId).when(appointmentMapper).insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(31L, 0)).thenReturn(0);

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.POOL_EXHAUSTED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        // 重读定性余量耗尽：1 次 CAS 即终止（重试至多 2 次的前置分支）
        verify(apptNumberPoolMapper, times(1)).casOccupy(eq(31L), anyInt());
        verify(poolRedisGate).release(eq(31L), eq(4L), any(Duration.class));
    }

    @Test
    @DisplayName("R1 book：CAS 耗尽路径回补 Redis 持有自身异常——不吞业务拒绝，仍判 OP-1003")
    void bookingStillRejectsWhenRedisReleaseFailsOnCasExhausted() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(1L);
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(3);
        when(apptNumberPoolMapper.casOccupy(eq(31L), anyInt())).thenReturn(0);
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(poolRedisGate)
                .release(anyLong(), anyLong(), any(Duration.class));

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.POOL_EXHAUSTED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(apptNumberPoolMapper, times(3)).casOccupy(eq(31L), anyInt());
    }

    @Test
    @DisplayName("R1 markTimeout：池行缺失——跳过回池（warn）但占位键删除与 credit 行照常")
    void markTimeoutSkipsPoolReleaseWhenPoolRowMissing() {
        Appointment held =
                reservedAppointment(ApptStatus.RESERVED, OffsetDateTime.now().minusMinutes(20), null);
        when(appointmentMapper.selectOne(any())).thenReturn(held);
        when(appointmentMapper.casStatus(101L, "RESERVED", "NO_SHOW")).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(null);
        when(apptCreditRecordMapper.selectCount(any())).thenReturn(0L);

        service.markTimeout(new AppointmentTimeoutPayload("AP20260920000001", 9L, 31L));

        verify(apptNumberPoolMapper, never()).casRelease(anyLong(), anyInt());
        verify(poolRedisGate, never()).release(anyLong(), anyLong(), any(Duration.class));
        verify(redisTemplate).delete("fy:outpatient:pay-hold:AP20260920000001");
        verify(apptCreditRecordMapper).insert(any(ApptCreditRecord.class));
    }

    @Test
    @DisplayName("R1 markTimeout：回池命中后排班缺失——Redis 快路径跳过（交日对账），credit 行照常")
    void markTimeoutSkipsRedisReleaseWhenScheduleMissing() {
        Appointment held =
                reservedAppointment(ApptStatus.RESERVED, OffsetDateTime.now().minusMinutes(20), null);
        when(appointmentMapper.selectOne(any())).thenReturn(held);
        when(appointmentMapper.casStatus(101L, "RESERVED", "NO_SHOW")).thenReturn(1);
        ApptNumberPool pool = activePool(PoolStatus.ACTIVE);
        pool.setVersion(7);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(pool);
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(1);
        when(scheduleMapper.selectById(11L)).thenReturn(null);
        when(apptCreditRecordMapper.selectCount(any())).thenReturn(0L);

        service.markTimeout(new AppointmentTimeoutPayload("AP20260920000001", 9L, 31L));

        verify(apptNumberPoolMapper).casRelease(31L, 7);
        verify(poolRedisGate, never()).release(anyLong(), anyLong(), any(Duration.class));
        verify(apptCreditRecordMapper).insert(any(ApptCreditRecord.class));
    }

    @Test
    @DisplayName("R1 markTimeout：Redis 快路径回补自身异常——不阻断释放面，credit 行照常落")
    void markTimeoutToleratesRedisReleaseFailure() {
        Appointment held =
                reservedAppointment(ApptStatus.RESERVED, OffsetDateTime.now().minusMinutes(20), null);
        when(appointmentMapper.selectOne(any())).thenReturn(held);
        when(appointmentMapper.casStatus(101L, "RESERVED", "NO_SHOW")).thenReturn(1);
        ApptNumberPool pool = activePool(PoolStatus.ACTIVE);
        pool.setVersion(7);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(pool);
        when(apptNumberPoolMapper.casRelease(31L, 7)).thenReturn(1);
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(poolRedisGate)
                .release(anyLong(), anyLong(), any(Duration.class));
        when(apptCreditRecordMapper.selectCount(any())).thenReturn(0L);

        service.markTimeout(new AppointmentTimeoutPayload("AP20260920000001", 9L, 31L));

        verify(apptNumberPoolMapper).casRelease(31L, 7);
        verify(redisTemplate).delete("fy:outpatient:pay-hold:AP20260920000001");
        verify(apptCreditRecordMapper).insert(any(ApptCreditRecord.class));
    }

    @Test
    @DisplayName("R1 markTimeout：预约单按 appt_no 定位失败——数据异常 fail-fast 交死信留痕")
    void markTimeoutFailsFastWhenAppointmentMissing() {
        when(appointmentMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.markTimeout(new AppointmentTimeoutPayload("AP20260920000001", 9L, 31L)))
                .isInstanceOf(IllegalStateException.class);
        verify(appointmentMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(poolRedisGate, never()).release(anyLong(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("R1 take：占位键删除自身异常（Redis 异常）不阻断取号——visit.registered 照常发布")
    void takeToleratesHoldKeyDeleteFailure() {
        String visitId = "O" + LocalDate.now().format(SEQ_DATE) + "00002";
        Appointment held =
                reservedAppointment(ApptStatus.RESERVED, OffsetDateTime.now().plusMinutes(5), null);
        when(appointmentMapper.selectOne(any())).thenReturn(held);
        when(visitIdIssuer.issue()).thenReturn(visitId);
        when(appointmentMapper.casTake(101L, visitId)).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(visitMapper.insert(any(Visit.class))).thenReturn(1);
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(redisTemplate)
                .delete(anyString());

        VisitVO vo = service.take("AP20260920000001");

        assertThat(vo.visitId()).isEqualTo(visitId);
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_VISIT_REGISTERED);
    }

    // ---------------------------------------------------------------- R1 覆盖率收口（PACKAGE LINE=1.00 补齐分支）

    @Test
    @DisplayName("R1 book：号源池不存在（poolId 无行）拒 OP-1002 404——排班读取零触达")
    void bookingRejectsWhenPoolMissing() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(null);

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.POOL_NOT_FOUND);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
        verify(scheduleMapper, never()).selectById(anyLong());
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    @DisplayName("R1 book：排班不存在（池行悬挂）拒 OP-1004——限约/限购零触达")
    void bookingRejectsWhenScheduleMissing() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(null);

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(apptCreditRecordMapper, never()).selectList(any());
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    @DisplayName("R1 book：池行余量谓词旁路（读回已约满）拒 OP-1003——Redis 预扣零触达")
    void bookingRejectsWhenPoolExhaustedAtRead() {
        ApptNumberPool exhausted = activePool(PoolStatus.ACTIVE);
        exhausted.setUsedCount(4);
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(exhausted);
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.POOL_EXHAUSTED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(poolRedisGate, never()).deduct(anyLong(), anyLong(), any(Duration.class));
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    @DisplayName("R1 take：casTake 成功后关联池行缺失（数据异常）fail-fast——visit 零落库")
    void takeFailsFastWhenAssociationsMissing() {
        String visitId = "O" + LocalDate.now().format(SEQ_DATE) + "00005";
        Appointment held =
                reservedAppointment(ApptStatus.RESERVED, OffsetDateTime.now().plusMinutes(5), null);
        when(appointmentMapper.selectOne(any())).thenReturn(held);
        when(visitIdIssuer.issue()).thenReturn(visitId);
        when(appointmentMapper.casTake(101L, visitId)).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(null);

        assertThatThrownBy(() -> service.take("AP20260920000001")).isInstanceOf(IllegalStateException.class);
        verify(visitMapper, never()).insert(any(Visit.class));
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("R1 book：降级路径（无 Redis 持有）CAS 耗尽判 OP-1003——零回补（回补面 redisHeld 短路）")
    void bookingCasExhaustedWithoutRedisHoldSkipsReplenish() {
        when(patientContextResolver.resolve(9L)).thenReturn(normalPatient());
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(activePool(PoolStatus.ACTIVE));
        when(scheduleMapper.selectById(11L)).thenReturn(schedule());
        when(apptCreditRecordMapper.selectList(any())).thenReturn(List.of());
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(
                        "fy:outpatient:appt-seq:" + LocalDate.now().format(SEQ_DATE)))
                .thenReturn(1L);
        // 键缺失降级（-2）：无第一道闸持有，失败路径回补面短路（redisHeld=false）
        when(poolRedisGate.deduct(eq(31L), eq(4L), any(Duration.class))).thenReturn(-2);
        doAnswer(this::stubInsertId).when(appointmentMapper).insert(any(Appointment.class));
        when(apptNumberPoolMapper.casOccupy(eq(31L), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> service.book(request("WINDOW"))).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.POOL_EXHAUSTED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(apptNumberPoolMapper, times(3)).casOccupy(eq(31L), anyInt());
        verify(poolRedisGate, never()).release(anyLong(), anyLong(), any(Duration.class));
    }
}
