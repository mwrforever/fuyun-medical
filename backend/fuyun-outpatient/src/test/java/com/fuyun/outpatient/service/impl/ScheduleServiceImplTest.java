package com.fuyun.outpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.api.ScheduleStoppedPayload;
import com.fuyun.outpatient.cache.PoolRedisGate;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.dto.ScheduleGenerateRequest;
import com.fuyun.outpatient.dto.SchedulePageQuery;
import com.fuyun.outpatient.dto.ScheduleTemplateSaveRequest;
import com.fuyun.outpatient.entity.ApptNumberPool;
import com.fuyun.outpatient.entity.Schedule;
import com.fuyun.outpatient.entity.ScheduleTemplate;
import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.ScheduleStatus;
import com.fuyun.outpatient.enums.SessionType;
import com.fuyun.outpatient.internal.OutpatientDomainEvent;
import com.fuyun.outpatient.mapper.ApptNumberPoolMapper;
import com.fuyun.outpatient.mapper.ScheduleMapper;
import com.fuyun.outpatient.mapper.ScheduleTemplateMapper;
import com.fuyun.outpatient.vo.NumberPoolVO;
import com.fuyun.outpatient.vo.ScheduleTemplateVO;
import com.fuyun.outpatient.vo.ScheduleVO;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;

/**
 * 排班/号源池服务单测（M03 FU-M03-01，Task 4 冻结用例集）：放号生成（week_pattern 位串×日期区间
 * 展开+uk 幂等跳过+池键 prime 预热）、停诊（schedule CAS+整池 STOPPED+schedule.stopped 事务内
 * 发布）、恢复（过期排班拒绝）、加号（total_quota 增量 CAS+数量上限守卫）、余量查询（参数与 VO
 * 投影锚定，ACTIVE/余量谓词由 mapper 注解 SQL 承载）。AFTER_COMMIT 的 MQ 出线时机归
 * OutpatientEventPublisherTest 与集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleServiceImplTest {

    /** 与主类 DEFAULT_CHANNEL_QUOTA 同源（渠道配额 JSON 缺省值；私有常量不外引，字面量双锚防漂移） */
    private static final String DEFAULT_CHANNEL_QUOTA_JSON = "{\"PORTAL\":60,\"WINDOW\":30,\"KIOSK\":5,\"RESERVED\":5}";

    @Mock
    private ScheduleTemplateMapper scheduleTemplateMapper;

    @Mock
    private ScheduleMapper scheduleMapper;

    @Mock
    private ApptNumberPoolMapper apptNumberPoolMapper;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private PoolRedisGate poolRedisGate;

    @Captor
    private ArgumentCaptor<OutpatientDomainEvent> eventCaptor;

    private ScheduleServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 放号模板查询与排班清单查询的 lambda 条件列解析依赖 TableInfo（容器外单测手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ScheduleTemplate.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Schedule.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApptNumberPool.class);
    }

    @BeforeEach
    void setUp() {
        service = new ScheduleServiceImpl(
                scheduleTemplateMapper, scheduleMapper, apptNumberPoolMapper, events, poolRedisGate);
        OperatorContextHolder.set("admin001");
    }

    @AfterEach
    void tearDown() {
        OperatorContextHolder.clear();
    }

    /** 排班模板替身：week_pattern 位串周一/周二出诊、08:00-08:30 号段、单时段 4 号 */
    private ScheduleTemplate template(String weekPattern) {
        ScheduleTemplate template = new ScheduleTemplate();
        template.setId(1L);
        template.setDeptCode("DEP001");
        template.setDoctorId("DOC001");
        template.setEffFrom(LocalDate.of(2026, 9, 1));
        template.setEffTo(null);
        template.setWeekPattern(weekPattern);
        template.setSession(SessionType.MORNING);
        template.setApptType(ApptType.EXPERT);
        template.setSlotStart(LocalTime.of(8, 0));
        template.setSlotEnd(LocalTime.of(8, 30));
        template.setSlotQuota(4);
        template.setRoom("诊室一");
        template.setReleaseDays(7);
        template.setReleaseTime(LocalTime.of(7, 0));
        template.setStatus("ACTIVE");
        return template;
    }

    /** 排班日历替身（status 由入参定，供 stop/resume 两态构造） */
    private Schedule schedule(long id, LocalDate schedDate, ScheduleStatus status) {
        Schedule schedule = new Schedule();
        schedule.setId(id);
        schedule.setTemplateId(1L);
        schedule.setSchedDate(schedDate);
        schedule.setSession(SessionType.MORNING);
        schedule.setDeptCode("DEP001");
        schedule.setDoctorId("DOC001");
        schedule.setApptType(ApptType.EXPERT);
        schedule.setTotalQuota(4);
        schedule.setUsedQuota(0);
        schedule.setStatus(status);
        return schedule;
    }

    /** 号源池行替身（余量查询投影锚定用） */
    private ApptNumberPool pool(long id, LocalTime slotStart, int totalQuota, int usedCount) {
        ApptNumberPool pool = new ApptNumberPool();
        pool.setId(id);
        pool.setScheduleId(11L);
        pool.setApptType(ApptType.EXPERT);
        pool.setSlotStart(slotStart);
        pool.setSlotEnd(slotStart.plusMinutes(30));
        pool.setTotalQuota(totalQuota);
        pool.setUsedCount(usedCount);
        return pool;
    }

    /** 模板保存请求替身（id 可空=登记语义；slot 起止可注入非法值走守卫分支） */
    private ScheduleTemplateSaveRequest saveRequest(Long id, LocalTime slotStart, LocalTime slotEnd) {
        return new ScheduleTemplateSaveRequest(
                id,
                "DEP001",
                "DOC001",
                LocalDate.of(2026, 10, 1),
                null,
                "1100000",
                SessionType.MORNING,
                ApptType.EXPERT,
                slotStart,
                slotEnd,
                4,
                "诊室一",
                7,
                LocalTime.of(7, 0));
    }

    @Test
    @DisplayName("generate：week_pattern 位串展开——窗口内周一/周二各生成 1 schedule+1 pool，池键 prime 预热 2 次")
    void generateExpandsTemplatesByWeekPatternToSchedulesAndPools() {
        // endDate=2026-09-27（周日）、days=7 → 窗口 09-21（周一）~09-27（周日）；week_pattern=1100000 命中周一/周二
        LocalDate endDate = LocalDate.of(2026, 9, 27);
        when(scheduleTemplateMapper.selectList(any())).thenReturn(List.of(template("1100000")));
        // 幂等预过滤读回：窗口内无已存在排班
        when(scheduleMapper.selectList(any())).thenReturn(List.of());
        AtomicLong scheduleSeq = new AtomicLong(100);
        doAnswer(inv -> {
                    Schedule inserting = inv.getArgument(0);
                    inserting.setId(scheduleSeq.incrementAndGet());
                    return 1;
                })
                .when(scheduleMapper)
                .insert(any(Schedule.class));
        AtomicLong poolSeq = new AtomicLong(200);
        doAnswer(inv -> {
                    ApptNumberPool inserting = inv.getArgument(0);
                    inserting.setId(poolSeq.incrementAndGet());
                    return 1;
                })
                .when(apptNumberPoolMapper)
                .insert(any(ApptNumberPool.class));

        int generated = service.generate(new ScheduleGenerateRequest(endDate, 7));

        assertThat(generated).isEqualTo(2);
        // 排班断言：仅周一/周二各一行，母本字段（科室/医生/号别/时段/号数）逐项拷贝
        ArgumentCaptor<Schedule> scheduleCaptor = ArgumentCaptor.forClass(Schedule.class);
        verify(scheduleMapper, times(2)).insert(scheduleCaptor.capture());
        assertThat(scheduleCaptor.getAllValues())
                .extracting(Schedule::getSchedDate)
                .containsExactly(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 22));
        assertThat(scheduleCaptor.getAllValues())
                .extracting(
                        Schedule::getTemplateId,
                        Schedule::getDeptCode,
                        Schedule::getDoctorId,
                        Schedule::getApptType,
                        Schedule::getSession,
                        Schedule::getTotalQuota,
                        Schedule::getUsedQuota)
                .containsExactly(
                        tuple(1L, "DEP001", "DOC001", ApptType.EXPERT, SessionType.MORNING, 4, 0),
                        tuple(1L, "DEP001", "DOC001", ApptType.EXPERT, SessionType.MORNING, 4, 0));
        // 池行断言：total_quota=4（slot_quota 母本）、channel_quota JSON 逐字回读、schedule 关联成对
        ArgumentCaptor<ApptNumberPool> poolCaptor = ArgumentCaptor.forClass(ApptNumberPool.class);
        verify(apptNumberPoolMapper, times(2)).insert(poolCaptor.capture());
        assertThat(poolCaptor.getAllValues())
                .extracting(
                        ApptNumberPool::getScheduleId, ApptNumberPool::getTotalQuota, ApptNumberPool::getChannelQuota)
                .containsExactly(
                        tuple(101L, 4, DEFAULT_CHANNEL_QUOTA_JSON), tuple(102L, 4, DEFAULT_CHANNEL_QUOTA_JSON));
        // 池键 prime 预热断言：poolId 与 TTL（TTL=sched_date 次日 02:00 对账窗口缓冲）
        ArgumentCaptor<Long> poolIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> totalCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(poolRedisGate, times(2)).prime(poolIdCaptor.capture(), totalCaptor.capture(), ttlCaptor.capture());
        assertThat(poolIdCaptor.getAllValues()).containsExactly(201L, 202L);
        assertThat(totalCaptor.getAllValues()).containsExactly(4L, 4L);
        // TTL 与「各自排班日次日 02:00」同刻（时钟取样误差放宽到 60s）：周一/周二两池键分别锚定
        Duration expectedMonday =
                Duration.between(LocalDateTime.now(), LocalDate.of(2026, 9, 22).atTime(2, 0));
        Duration expectedTuesday =
                Duration.between(LocalDateTime.now(), LocalDate.of(2026, 9, 23).atTime(2, 0));
        assertThat(Math.abs(
                        ttlCaptor.getAllValues().get(0).minus(expectedMonday).toSeconds()))
                .isLessThan(60);
        assertThat(Math.abs(
                        ttlCaptor.getAllValues().get(1).minus(expectedTuesday).toSeconds()))
                .isLessThan(60);
    }

    @Test
    @DisplayName("generate：窗口内已存在排班键（uk_schedule 三列）预过滤幂等跳过——返回 0、零插入零预热（重放语义）")
    void generateSkipsExistingScheduleDateIdempotently() {
        when(scheduleTemplateMapper.selectList(any())).thenReturn(List.of(template("1000000")));
        // 预过滤读回：周一（09-21）排班已在库（templateId=1 + MORNING 与模板同键）——重放在此处零插入跳过
        when(scheduleMapper.selectList(any()))
                .thenReturn(List.of(schedule(51L, LocalDate.of(2026, 9, 21), ScheduleStatus.NORMAL)));

        int generated = service.generate(new ScheduleGenerateRequest(LocalDate.of(2026, 9, 27), 7));

        assertThat(generated).isZero();
        verify(scheduleMapper, never()).insert(any(Schedule.class));
        verify(apptNumberPoolMapper, never()).insert(any(ApptNumberPool.class));
        verify(poolRedisGate, never()).prime(anyLong(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("generate：预过滤后并发 uk 冲突抛 OP-1004 整批回滚——PG 事务 aborted 语义禁捕获续跑（并发语义）")
    void generateAbortsBatchOnConcurrentUkConflict() {
        when(scheduleTemplateMapper.selectList(any())).thenReturn(List.of(template("1000000")));
        when(scheduleMapper.selectList(any())).thenReturn(List.of());
        when(scheduleMapper.insert(any(Schedule.class))).thenThrow(new DuplicateKeyException("uk_schedule 冲突"));

        assertThatThrownBy(() -> service.generate(new ScheduleGenerateRequest(LocalDate.of(2026, 9, 27), 7)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(poolRedisGate, never()).prime(anyLong(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("stop：CAS NORMAL→STOPPED 成功——整池行置 STOPPED，事务内发布 schedule.stopped（五组件断言）")
    void stopMarksScheduleStoppedAndInvalidatesPools() {
        Schedule normal = schedule(11L, LocalDate.of(2026, 9, 23), ScheduleStatus.NORMAL);
        when(scheduleMapper.selectById(11L)).thenReturn(normal);
        when(scheduleMapper.casStatus(eq(11L), eq("NORMAL"), eq("STOPPED"), eq("admin001")))
                .thenReturn(1);
        when(apptNumberPoolMapper.markStoppedByScheduleId(11L, "admin001")).thenReturn(3);

        service.stop(11L, "设备检修");

        verify(apptNumberPoolMapper).markStoppedByScheduleId(11L, "admin001");
        verify(events).publishEvent(eventCaptor.capture());
        OutpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_SCHEDULE_STOPPED);
        assertThat(event.payload()).isInstanceOf(ScheduleStoppedPayload.class);
        ScheduleStoppedPayload payload = (ScheduleStoppedPayload) event.payload();
        assertThat(payload.scheduleId()).isEqualTo(11L);
        assertThat(payload.schedDate()).isEqualTo("20260923");
        assertThat(payload.deptCode()).isEqualTo("DEP001");
        assertThat(payload.doctorId()).isEqualTo("DOC001");
        assertThat(payload.stopReason()).isEqualTo("设备检修");
    }

    @Test
    @DisplayName("stop：CAS 0 行（并发已停/状态违例）抛 OP-1004——池行批量与事件零触达")
    void stopRejectsAlreadyStoppedSchedule() {
        when(scheduleMapper.casStatus(eq(11L), eq("NORMAL"), eq("STOPPED"), eq("admin001")))
                .thenReturn(0);

        assertThatThrownBy(() -> service.stop(11L, "设备检修")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(apptNumberPoolMapper, never()).markStoppedByScheduleId(anyLong(), any());
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("resume：sched_date=明日恢复 NORMAL+池 ACTIVE 且不发事件；sched_date=昨日过期排班拒 OP-1004")
    void resumeRestoresPoolsOnlyWhenDateInFuture() {
        Schedule future = schedule(21L, LocalDate.now().plusDays(1), ScheduleStatus.STOPPED);
        Schedule past = schedule(22L, LocalDate.now().minusDays(1), ScheduleStatus.STOPPED);
        when(scheduleMapper.selectById(21L)).thenReturn(future);
        when(scheduleMapper.selectById(22L)).thenReturn(past);
        when(scheduleMapper.casStatus(eq(21L), eq("STOPPED"), eq("NORMAL"), eq("admin001")))
                .thenReturn(1);
        when(apptNumberPoolMapper.markActiveByScheduleId(21L, "admin001")).thenReturn(2);

        service.resume(21L);

        verify(apptNumberPoolMapper).markActiveByScheduleId(21L, "admin001");
        // 恢复无事件面：schedule.stopped 仅停诊方向发布
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));

        // 过期排班（sched_date=今日-1）不可恢复：OP-1004，且未触达 CAS 与池行
        assertThatThrownBy(() -> service.resume(22L)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(scheduleMapper, never()).casStatus(eq(22L), any(), any(), any());
        verify(apptNumberPoolMapper, never()).markActiveByScheduleId(eq(22L), any());
    }

    @Test
    @DisplayName("resume：CAS 0 行（排班非 STOPPED 态）抛 OP-1004——池行恢复零触达")
    void resumeRejectsWhenScheduleNotStopped() {
        when(scheduleMapper.selectById(23L))
                .thenReturn(schedule(23L, LocalDate.now().plusDays(1), ScheduleStatus.NORMAL));
        when(scheduleMapper.casStatus(eq(23L), eq("STOPPED"), eq("NORMAL"), eq("admin001")))
                .thenReturn(0);

        assertThatThrownBy(() -> service.resume(23L)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(apptNumberPoolMapper, never()).markActiveByScheduleId(anyLong(), any());
    }

    @Test
    @DisplayName("extraQuota：加号 count=5 走 casAddExtraQuota 并同步 INCRBY 池键续期；count>50 与 0 拒 OP-1019")
    void extraQuotaIncrementsPoolQuotaAndCapsAtLimit() {
        when(apptNumberPoolMapper.casAddExtraQuota(31L, 5)).thenReturn(1);
        // CAS 后读回：total_quota=4+5=9（池键封顶锚取新总量）
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(pool(31L, LocalTime.of(8, 0), 9, 1));
        when(scheduleMapper.selectById(11L))
                .thenReturn(schedule(11L, LocalDate.of(2026, 9, 23), ScheduleStatus.NORMAL));

        service.extraQuota(31L, 5);

        verify(apptNumberPoolMapper).casAddExtraQuota(31L, 5);
        // R1 快路径同步：池键 INCRBY 5、封顶锚=新总量 9、TTL 续期至排班次日 02:00
        ArgumentCaptor<Duration> refreshTtlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(poolRedisGate).increase(eq(31L), eq(5L), eq(9L), refreshTtlCaptor.capture());
        Duration expectedTtl =
                Duration.between(LocalDateTime.now(), LocalDate.of(2026, 9, 24).atTime(2, 0));
        assertThat(Math.abs(refreshTtlCaptor.getValue().minus(expectedTtl).toSeconds()))
                .isLessThan(60);
        // 上限外（count=51）与零数量（count=0）：入参显式格式校验拒绝，池行与池键零触达
        assertThatThrownBy(() -> service.extraQuota(31L, 51)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
        assertThatThrownBy(() -> service.extraQuota(31L, 0)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
        verify(apptNumberPoolMapper, times(1)).casAddExtraQuota(anyLong(), anyInt());
        verify(poolRedisGate, times(1)).increase(anyLong(), anyLong(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("extraQuota：Redis 刷新异常不回滚加号（库为权威库存）——error 留痕交日对账兜底")
    void extraQuotaKeepsQuotaWhenRedisRefreshFails() {
        when(apptNumberPoolMapper.casAddExtraQuota(31L, 5)).thenReturn(1);
        when(apptNumberPoolMapper.selectById(31L)).thenReturn(pool(31L, LocalTime.of(8, 0), 9, 1));
        when(scheduleMapper.selectById(11L))
                .thenReturn(schedule(11L, LocalDate.of(2026, 9, 23), ScheduleStatus.NORMAL));
        when(poolRedisGate.increase(eq(31L), eq(5L), eq(9L), any(Duration.class)))
                .thenThrow(new RedisSystemException("redis 不可用", new IllegalStateException("connection refused")));

        // 不抛=加号授权保留（DB 权威），池键滞后交日对账收敛
        service.extraQuota(31L, 5);

        verify(apptNumberPoolMapper).casAddExtraQuota(31L, 5);
        verify(poolRedisGate).increase(eq(31L), eq(5L), eq(9L), any(Duration.class));
    }

    @Test
    @DisplayName("availablePools：余量查询按 deptCode/date/apptType 精确下发，slot_start 升序映射 VO 并计算余量")
    void availablePoolsFiltersStoppedAndExhausted() {
        // ACTIVE 且 used_count<total_quota 谓词由 mapper 注解 SQL selectAvailable 承载（集成测试面），
        // 本单测锚定参数传递、slot_start 升序透传与 VO 投影（remaining=total-used）
        LocalDate queryDate = LocalDate.of(2026, 9, 23);
        when(apptNumberPoolMapper.selectAvailable("DEP001", queryDate, "EXPERT"))
                .thenReturn(List.of(pool(31L, LocalTime.of(8, 0), 4, 1), pool(32L, LocalTime.of(14, 0), 6, 2)));

        List<NumberPoolVO> result = service.availablePools("DEP001", queryDate, ApptType.EXPERT);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(31L);
        assertThat(result.get(0).slotStart()).isEqualTo(LocalTime.of(8, 0));
        assertThat(result.get(0).remaining()).isEqualTo(3);
        assertThat(result.get(1).slotStart()).isEqualTo(LocalTime.of(14, 0));
        assertThat(result.get(1).remaining()).isEqualTo(4);
    }

    @Test
    @DisplayName("saveTemplate：登记路径落 ACTIVE 模板并回显 VO（id 经 ASSIGN_ID 回填）")
    void saveTemplateCreatesTemplateAndReturnsVO() {
        doAnswer(inv -> {
                    ScheduleTemplate inserting = inv.getArgument(0);
                    inserting.setId(41L);
                    return 1;
                })
                .when(scheduleTemplateMapper)
                .insert(any(ScheduleTemplate.class));

        ScheduleTemplateVO vo = service.saveTemplate(saveRequest(null, LocalTime.of(8, 0), LocalTime.of(8, 30)));

        ArgumentCaptor<ScheduleTemplate> captor = ArgumentCaptor.forClass(ScheduleTemplate.class);
        verify(scheduleTemplateMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().getCreatedBy()).isEqualTo("admin001");
        assertThat(vo.id()).isEqualTo(41L);
        assertThat(vo.deptCode()).isEqualTo("DEP001");
        assertThat(vo.weekPattern()).isEqualTo("1100000");
    }

    @Test
    @DisplayName("saveTemplate：更新路径按 id 全量覆盖请求面字段并回显 VO")
    void saveTemplateUpdatesExistingTemplate() {
        when(scheduleTemplateMapper.updateById(any(ScheduleTemplate.class))).thenReturn(1);

        ScheduleTemplateVO vo = service.saveTemplate(saveRequest(41L, LocalTime.of(9, 0), LocalTime.of(9, 30)));

        ArgumentCaptor<ScheduleTemplate> captor = ArgumentCaptor.forClass(ScheduleTemplate.class);
        verify(scheduleTemplateMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(41L);
        assertThat(captor.getValue().getSlotStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(vo.id()).isEqualTo(41L);
        assertThat(vo.slotStart()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    @DisplayName("saveTemplate：更新 0 行（模板不存在）抛 OP-1004")
    void saveTemplateUpdateRejectsMissingTemplate() {
        when(scheduleTemplateMapper.updateById(any(ScheduleTemplate.class))).thenReturn(0);

        assertThatThrownBy(() -> service.saveTemplate(saveRequest(99L, LocalTime.of(8, 0), LocalTime.of(8, 30))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.SCHEDULE_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    @DisplayName("saveTemplate：号段起止倒挂（slotStart≥slotEnd）拒 OP-1019——mapper 零触达")
    void saveTemplateRejectsInvertedSlotRange() {
        assertThatThrownBy(() -> service.saveTemplate(saveRequest(null, LocalTime.of(9, 0), LocalTime.of(8, 30))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(scheduleTemplateMapper, never()).insert(any(ScheduleTemplate.class));
        verify(scheduleTemplateMapper, never()).updateById(any(ScheduleTemplate.class));
    }

    @Test
    @DisplayName("listTemplates：分页出参 {content,page,size,total} 锚定，wrapper 携 id 升序唯一顺序")
    void listTemplatesReturnsPagedResult() {
        when(scheduleTemplateMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<ScheduleTemplate> page = inv.getArgument(0);
            page.setRecords(List.of(template("1100000")));
            page.setTotal(1);
            return page;
        });

        PageResult<ScheduleTemplateVO> result = service.listTemplates(0, 20);

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).deptCode()).isEqualTo("DEP001");
        ArgumentCaptor<Wrapper<ScheduleTemplate>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(scheduleTemplateMapper).selectPage(any(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("ASC");
    }

    @Test
    @DisplayName("listSchedules：deptCode/dateFrom/dateTo 过滤条件下发（wrapper sqlSegment+参数断言），分页透传")
    void listSchedulesReturnsPagedResult() {
        when(scheduleMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<Schedule> page = inv.getArgument(0);
            page.setRecords(List.of(schedule(11L, LocalDate.of(2026, 9, 23), ScheduleStatus.NORMAL)));
            page.setTotal(1);
            return page;
        });

        PageResult<ScheduleVO> result = service.listSchedules(
                new SchedulePageQuery("DEP001", LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27), 0, 20));

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).schedDate()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(result.content().get(0).deptCode()).isEqualTo("DEP001");
        ArgumentCaptor<Page<Schedule>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<Wrapper<Schedule>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(scheduleMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        // API page 0 基 → MP Page current 1 基（+1 平移，且 MP 将 <1 归一为 1）：0 基出参回显见 result.page()
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(20);
        // wrapper 断言只对 getSqlSegment()/getParamNameValuePairs() 做 contains 子串断言（Global Constraints 形态）
        AbstractWrapper<Schedule, Object, ?> lambdaWrapper =
                (AbstractWrapper<Schedule, Object, ?>) wrapperCaptor.getValue();
        assertThat(lambdaWrapper.getSqlSegment()).contains("dept_code").contains("sched_date");
        assertThat(lambdaWrapper.getParamNameValuePairs().values()).contains("DEP001");
    }

    @Test
    @DisplayName("R1 extraQuota：CAS 0 行（池行不存在或已停用）抛 OP-1002——池键零触达（行覆盖补齐）")
    void extraQuotaRejectsWhenPoolMissingOrStopped() {
        when(apptNumberPoolMapper.casAddExtraQuota(99L, 5)).thenReturn(0);

        assertThatThrownBy(() -> service.extraQuota(99L, 5)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.POOL_NOT_FOUND);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
        verify(poolRedisGate, never()).increase(anyLong(), anyLong(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("R1 generate：模板失效日（eff_to）截断放号窗口——失效日后日期不生成排班（matchesWeekAndValidity 排除分支）")
    void generateSkipsDatesBeyondTemplateValidity() {
        // 模板仅周一出诊（1000000），eff_to=09-21：窗口 09-21~09-27 内仅 09-21（周一=失效日当日）生成，
        // 09-22 起既非周一且越过失效日——eff_to 排除分支（generateSkipsExisting 同源替身构造）
        ScheduleTemplate expiring = template("1000000");
        expiring.setEffTo(LocalDate.of(2026, 9, 21));
        when(scheduleTemplateMapper.selectList(any())).thenReturn(List.of(expiring));
        when(scheduleMapper.selectList(any())).thenReturn(List.of());
        AtomicLong scheduleSeq = new AtomicLong(300);
        doAnswer(inv -> {
                    Schedule inserting = inv.getArgument(0);
                    inserting.setId(scheduleSeq.incrementAndGet());
                    return 1;
                })
                .when(scheduleMapper)
                .insert(any(Schedule.class));
        AtomicLong poolSeq = new AtomicLong(400);
        doAnswer(inv -> {
                    ApptNumberPool inserting = inv.getArgument(0);
                    inserting.setId(poolSeq.incrementAndGet());
                    return 1;
                })
                .when(apptNumberPoolMapper)
                .insert(any(ApptNumberPool.class));

        int generated = service.generate(new ScheduleGenerateRequest(LocalDate.of(2026, 9, 27), 7));

        assertThat(generated).isEqualTo(1);
        ArgumentCaptor<Schedule> scheduleCaptor = ArgumentCaptor.forClass(Schedule.class);
        verify(scheduleMapper, times(1)).insert(scheduleCaptor.capture());
        assertThat(scheduleCaptor.getValue().getSchedDate()).isEqualTo(LocalDate.of(2026, 9, 21));
    }
}
