package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.nursing.api.AdverseEventReportedPayload;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.dto.AdverseEventCloseRequest;
import com.fuyun.nursing.dto.AdverseEventHandleRequest;
import com.fuyun.nursing.dto.AdverseEventReportRequest;
import com.fuyun.nursing.dto.AdverseEventReturnRequest;
import com.fuyun.nursing.entity.AdverseEvent;
import com.fuyun.nursing.enums.AdverseEventStatus;
import com.fuyun.nursing.internal.NurseBoardPushEvent;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.AdverseEventMapper;
import com.fuyun.nursing.vo.AdverseEventStatsVO;
import com.fuyun.nursing.vo.AdverseEventVO;
import com.fuyun.nursing.vo.NurseBoardPushFrame;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 不良事件域单测（Task 10 brief 冻结用例组①–⑤ + 红线锚 + PR-4C Task 6 W-40 绑定集过滤组）：
 * 上报（I 级 deadline 落库+id 83 事件发布/匿名通道/III-IV 级无时限/超时补报留痕/词表与未来时刻
 * 守卫）、handle/close/return 状态机三路+非法迁移 NS-1026、stats 聚合、tick 超时提醒扫描段
 * （只读不改状态）、list/stats wardScope 过滤（携 wardId 单值/绑定集 in/空集防御短路）；
 * 非惩罚红线反射锚（VO/统计出参零 reporter 字段）。MP 3.5.17 单测范式：lambdaQuery 触达
 * 实体 @BeforeAll 手工注册表信息。
 */
@ExtendWith(MockitoExtension.class)
class AdverseEventServiceImplTest {

    /** 不良事件号（业务号断言基准） */
    private static final String AE_NO = "AE2026100200001";

    /** 病区编码 */
    private static final String WARD = "W01";

    /** 上报人员工 ID（非匿名面） */
    private static final long REPORTER = 1001L;

    /** 处置责任人员工 ID */
    private static final long HANDLER = 2001L;

    /** 关闭/退回动作主体员工 ID */
    private static final long CLOSER = 3001L;

    /** 北京钟面偏移（occurredAt 构造确定性锚——禁容器时区漂移用例时点） */
    private static final ZoneOffset BEIJING = ZoneOffset.ofHours(8);

    @Mock
    private AdverseEventMapper mapper;

    @Mock
    private NursingSeqGate seqGate;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<AdverseEvent> rowCaptor;

    @Captor
    private ArgumentCaptor<Object> eventCaptor;

    private AdverseEventServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（不良事件主表）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AdverseEvent.class);
    }

    @BeforeEach
    void setUp() {
        service = new AdverseEventServiceImpl(mapper, seqGate, events);
        // 链式 lambdaQuery（A.4.3-13）走 getEntityClass（经 mapper 代理元数据解析），mock 下须显式注入
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "entityClass", AdverseEvent.class);
        // REST 链路操作者=登录护士（ThreadLocal 自然透传口径，GC15）
        OperatorContextHolder.set(String.valueOf(REPORTER));
    }

    @AfterEach
    void clearContext() {
        // 防御性清理：防操作者上下文串号泄漏到其他用例的审计断言
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("① 上报 I 级：deadline=occurredAt+24h 落库+deadline_met 按时 true+id 83 事件发布（五字段逐位）")
    void reportLevelIDeadlineAndEventPublish() {
        OffsetDateTime occurredAt = OffsetDateTime.now(BEIJING).minusHours(2);
        when(seqGate.nextNo("AE")).thenReturn(AE_NO);

        AdverseEventVO vo = service.report(report("FALL", "I", "C", occurredAt, REPORTER, false));

        assertThat(vo.eventNo()).isEqualTo(AE_NO);
        assertThat(vo.status()).isEqualTo(AdverseEventStatus.REPORTED.getCode());
        // 数据库写操作：落库行断言——I 级 deadline=occurredAt+24h、上报即判定按时 true、处置记录空串承载
        verify(mapper).insert(rowCaptor.capture());
        AdverseEvent row = rowCaptor.getValue();
        assertThat(row.getReportDeadline()).isEqualTo(occurredAt.plusHours(24));
        assertThat(row.getDeadlineMet()).isTrue();
        assertThat(row.getReporterId()).isEqualTo(REPORTER);
        assertThat(row.getIsAnonymous()).isFalse();
        assertThat(row.getHandlingNote()).isEmpty();
        assertThat(row.getStatus()).isEqualTo(AdverseEventStatus.REPORTED.getCode());
        // 消息发送：事务内发布 id 83 事件（五字段冻结契约逐位断言——匿名面无 reporter 字段天然合规）
        verify(events).publishEvent(eventCaptor.capture());
        NursingDomainEvent event = (NursingDomainEvent) eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(NursingMessagingConstants.EVENT_ADVERSE_EVENT_REPORTED);
        AdverseEventReportedPayload payload = (AdverseEventReportedPayload) event.payload();
        assertThat(payload.eventNo()).isEqualTo(AE_NO);
        assertThat(payload.category()).isEqualTo("FALL");
        assertThat(payload.severityClass()).isEqualTo("I");
        assertThat(payload.wardId()).isEqualTo(WARD);
        assertThat(payload.occurredAt()).isEqualTo(occurredAt.toInstant());
    }

    @Test
    @DisplayName("①b II 级超时补报：occurredAt 已越 24h——deadline_met=false 留痕（非惩罚不拒绝）")
    void reportLevelIILateSupplementMarksDeadlineMissed() {
        OffsetDateTime occurredAt = OffsetDateTime.now(BEIJING).minusHours(30);
        when(seqGate.nextNo("AE")).thenReturn(AE_NO);

        service.report(report("FALL", "II", "B", occurredAt, REPORTER, false));

        verify(mapper).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getReportDeadline()).isEqualTo(occurredAt.plusHours(24));
        assertThat(rowCaptor.getValue().getDeadlineMet()).isFalse();
    }

    @Test
    @DisplayName("①c III/IV 级：不预置 24h 时限——report_deadline/deadline_met 双 NULL 落库")
    void reportLevelIVWithoutDeadline() {
        when(seqGate.nextNo("AE")).thenReturn(AE_NO);

        service.report(report("OTHER", "IV", "A", OffsetDateTime.now(BEIJING).minusHours(1), REPORTER, false));

        verify(mapper).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getReportDeadline()).isNull();
        assertThat(rowCaptor.getValue().getDeadlineMet()).isNull();
        // 上报事件照发（id 83 与分级无关——III/IV 级同样入 M19 统计面）
        verify(events).publishEvent(any(NursingDomainEvent.class));
    }

    @Test
    @DisplayName("② 匿名通道：显式 isAnonymous=true 携 reporter 仍强制置空；未声明匿名默认令牌实名（W-72 归一取消）")
    void anonymousChannelNullsReporterOnlyWhenExplicit() {
        when(seqGate.nextNo("AE")).thenReturn(AE_NO);
        OffsetDateTime occurredAt = OffsetDateTime.now(BEIJING).minusHours(1);

        // 显式匿名携上报人：强制置空（非惩罚红线——匿名通道不留个人面，不取令牌）
        service.report(report("FALL", "I", "C", occurredAt, REPORTER, true));
        verify(mapper, org.mockito.Mockito.times(1)).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getReporterId()).isNull();
        assertThat(rowCaptor.getValue().getIsAnonymous()).isTrue();

        // 未携上报人未声明匿名：默认实名落令牌（W-72 取消「reporterId==null 即匿名」归一——
        // 身份已转令牌承载，未声明匿名不再隐式归一）
        service.report(report("FALL", "I", "C", occurredAt, null, null));
        verify(mapper, org.mockito.Mockito.times(2)).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getReporterId()).isEqualTo(REPORTER);
        assertThat(rowCaptor.getValue().getIsAnonymous()).isFalse();
    }

    @Test
    @DisplayName("W-72 上报：显式匿名走通道（reporter NULL、不取令牌）；非匿名一律令牌实名")
    void reportAttributionFollowsAnonymousFlagAndToken() {
        when(seqGate.nextNo("AE")).thenReturn(AE_NO);

        // 非匿名：请求体 reporterId 携带差异值 999 → 落令牌 REPORTER（差异值忽略）
        AdverseEventVO vo = service.report(reportRequest(999L, false));
        assertThat(vo.isAnonymous()).isFalse();
        // 匿名：显式 isAnonymous=true → reporter_id 落 NULL（通道语义保留，不调用令牌取值）
        AdverseEventVO anon = service.report(reportRequest(999L, true));
        assertThat(anon.isAnonymous()).isTrue();

        verify(mapper, org.mockito.Mockito.times(2)).insert(rowCaptor.capture());
        // 落库锁定锚：非匿名行 reporter_id=令牌身份、匿名行 reporter_id=NULL（请求体 999 两路均不落库）
        assertThat(rowCaptor.getAllValues().get(0).getReporterId()).isEqualTo(REPORTER);
        assertThat(rowCaptor.getAllValues().get(1).getReporterId()).isNull();
    }

    @Test
    @DisplayName("W-72 实名守卫：操作者上下文缺失时非匿名上报拒 NS-1019（fail-closed——实名主体须可定位）")
    void reportRejectsMissingOperatorContextForRealName() {
        OperatorContextHolder.clear();

        assertThatThrownBy(() -> service.report(
                        report("FALL", "I", "C", OffsetDateTime.now(BEIJING).minusHours(1), REPORTER, false)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                });
        // 实名主体不可定位即拒绝：零落库零事件（匿名通道不受影响——不取令牌）
        verify(mapper, never()).insert(any(AdverseEvent.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("W-72 实名守卫：令牌身份非空但非数字拒 NS-1019（评审 E-1 补覆盖——fail-closed）")
    void reportRejectsNonNumericOperatorContextForRealName() {
        // 非空非数字令牌（评审 E-1：该守卫分支此前零覆盖，既有用例只测 clear() 缺失路径）
        OperatorContextHolder.set("operator-x");

        assertThatThrownBy(() -> service.report(
                        report("FALL", "I", "C", OffsetDateTime.now(BEIJING).minusHours(1), REPORTER, false)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                });
        // 令牌身份不可解析为员工 ID 即拒绝：零落库零事件（与缺失路径同款 fail-closed 面）
        verify(mapper, never()).insert(any(AdverseEvent.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("W-72 处置/关闭/退回：留痕人一律令牌身份（请求体差异值忽略）")
    void handleCloseReturnUseTokenIdentity() {
        // 处置：请求体 handlerId=888（差异值），handler_id=令牌 REPORTER
        when(mapper.selectOne(any())).thenReturn(row(AdverseEventStatus.REPORTED));
        when(mapper.casHandle(eq(AE_NO), eq(REPORTER), any(), any(), any())).thenReturn(1);

        AdverseEventVO handled = service.handle(AE_NO, new AdverseEventHandleRequest(888L, "已处置"));

        assertThat(handled.handlerId()).isEqualTo(REPORTER);
        verify(mapper).casHandle(eq(AE_NO), eq(REPORTER), eq("已处置"), any(), any());

        // 关闭：请求体 closedBy=888（差异值），updated_by 文本=令牌十进制串
        when(mapper.selectOne(any())).thenReturn(row(AdverseEventStatus.HANDLING));
        when(mapper.casClose(any(), any(), any(), any())).thenReturn(1);
        service.close(AE_NO, new AdverseEventCloseRequest(null, null, 888L));
        verify(mapper).casClose(eq(AE_NO), any(), any(), eq(String.valueOf(REPORTER)));

        // 退回：请求体 returnerId=888（差异值），updated_by 文本=令牌十进制串
        when(mapper.casReturn(any(), any(), any())).thenReturn(1);
        service.returnEvent(AE_NO, new AdverseEventReturnRequest("处置不充分", 888L));
        verify(mapper).casReturn(eq(AE_NO), eq("处置不充分"), eq(String.valueOf(REPORTER)));
    }

    @Test
    @DisplayName("③ handle：REPORTED→HANDLING CAS；I/II 级超时行 deadline_met=false 留痕不阻断")
    void handleMigratesWithOverdueMarking() {
        AdverseEvent target = row(AdverseEventStatus.REPORTED);
        target.setReportDeadline(OffsetDateTime.now(BEIJING).minusHours(1));
        target.setDeadlineMet(true);
        when(mapper.selectOne(any())).thenReturn(target);
        // W-72：处置人=令牌身份（请求体 HANDLER 差异值忽略）
        when(mapper.casHandle(eq(AE_NO), eq(REPORTER), any(), any(), any())).thenReturn(1);

        AdverseEventVO vo = service.handle(AE_NO, new AdverseEventHandleRequest(HANDLER, "已到场处置"));

        assertThat(vo.status()).isEqualTo(AdverseEventStatus.HANDLING.getCode());
        assertThat(vo.handlerId()).isEqualTo(REPORTER);
        // 超时留痕断言：deadline 已过→CAS 收到 false（非惩罚：不阻断，仅 deadline_met 落 false）
        verify(mapper).casHandle(eq(AE_NO), eq(REPORTER), eq("已到场处置"), eq(Boolean.FALSE), any());
    }

    @Test
    @DisplayName("③b handle 未超时行：deadline_met 透传行原值（true 保留——上报时已按时）")
    void handleWithinDeadlineKeepsMetFlag() {
        AdverseEvent target = row(AdverseEventStatus.REPORTED);
        target.setReportDeadline(OffsetDateTime.now(BEIJING).plusHours(10));
        target.setDeadlineMet(true);
        when(mapper.selectOne(any())).thenReturn(target);
        when(mapper.casHandle(eq(AE_NO), eq(REPORTER), any(), any(), any())).thenReturn(1);

        service.handle(AE_NO, new AdverseEventHandleRequest(HANDLER, null));

        // 未超时+处置记录可空：CAS 收到行原值 true、handlingNote null 透传（COALESCE 保留上报时记录）
        verify(mapper).casHandle(eq(AE_NO), eq(REPORTER), eq(null), eq(Boolean.TRUE), any());
    }

    @Test
    @DisplayName("③c 非法迁移三路：handle/close/return CAS 零行一律 NS-1026（状态机违例）")
    void illegalTransitionsRejectedWithStateNotAllowed() {
        when(mapper.selectOne(any())).thenReturn(row(AdverseEventStatus.CLOSED));
        when(mapper.casHandle(any(), anyLong(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.handle(AE_NO, new AdverseEventHandleRequest(HANDLER, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.ADVERSE_EVENT_STATE_NOT_ALLOWED));
        // close/return 走各自 CAS 零行（同 NS-1026 面）
        when(mapper.casClose(any(), any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.close(AE_NO, new AdverseEventCloseRequest(null, null, CLOSER)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.ADVERSE_EVENT_STATE_NOT_ALLOWED));
        when(mapper.casReturn(any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.returnEvent(AE_NO, new AdverseEventReturnRequest("处置不充分", CLOSER)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.ADVERSE_EVENT_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("③d 不存在三路：eventNo 定位失败一律 NS-1025（404 fail-closed）")
    void notFoundRejectedForAllActionPaths() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.handle(AE_NO, new AdverseEventHandleRequest(HANDLER, null)))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.ADVERSE_EVENT_NOT_FOUND));
        assertThatThrownBy(() -> service.close(AE_NO, new AdverseEventCloseRequest(null, null, CLOSER)))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.ADVERSE_EVENT_NOT_FOUND));
        assertThatThrownBy(() -> service.returnEvent(AE_NO, new AdverseEventReturnRequest("退回原因", CLOSER)))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.ADVERSE_EVENT_NOT_FOUND));
        verify(mapper, never()).casHandle(any(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("③e close：HANDLING→CLOSED + RCA 与整改措施落库；closedBy 经 updated_by 审计承载")
    void closeStoresRcaAndCorrectiveAction() {
        AdverseEvent target = row(AdverseEventStatus.HANDLING);
        when(mapper.selectOne(any())).thenReturn(target);
        when(mapper.casClose(any(), any(), any(), any())).thenReturn(1);

        AdverseEventVO vo = service.close(AE_NO, new AdverseEventCloseRequest("根因：巡视间隔过长", "整改：q2h 巡视落地", CLOSER));

        assertThat(vo.status()).isEqualTo(AdverseEventStatus.CLOSED.getCode());
        assertThat(vo.rcaNote()).isEqualTo("根因：巡视间隔过长");
        assertThat(vo.correctiveAction()).isEqualTo("整改：q2h 巡视落地");
        // 动作主体断言：V1107 无 closed_by 列——closedBy 文本经 updated_by 审计列承载；
        // W-72：一律令牌身份（请求体 CLOSER 差异值忽略）
        verify(mapper).casClose(eq(AE_NO), eq("根因：巡视间隔过长"), eq("整改：q2h 巡视落地"), eq(String.valueOf(REPORTER)));
    }

    @Test
    @DisplayName("③f return：HANDLING→REPORTED 侧支+退回原因覆写处置记录；returnerId 经 updated_by 承载")
    void returnRevertsToReportedWithReason() {
        AdverseEvent target = row(AdverseEventStatus.HANDLING);
        when(mapper.selectOne(any())).thenReturn(target);
        when(mapper.casReturn(any(), any(), any())).thenReturn(1);

        AdverseEventVO vo = service.returnEvent(AE_NO, new AdverseEventReturnRequest("处置记录不完整需补充", CLOSER));

        assertThat(vo.status()).isEqualTo(AdverseEventStatus.REPORTED.getCode());
        assertThat(vo.handlingNote()).isEqualTo("处置记录不完整需补充");
        // 动作主体断言：returnerId 文本经 updated_by 承载；W-72：一律令牌身份（请求体 CLOSER 差异值忽略）
        verify(mapper).casReturn(eq(AE_NO), eq("处置记录不完整需补充"), eq(String.valueOf(REPORTER)));
    }

    @Test
    @DisplayName("④ stats 聚合：类别/分级/等级/病区/班次五维计数+I/II 级时限合规面+词表零填充")
    void statsAggregatesAllDimensions() {
        AdverseEvent morning = row(AdverseEventStatus.REPORTED);
        morning.setCategory("FALL");
        morning.setSeverityClass("I");
        morning.setSeverityGrade("C");
        morning.setWardId("W01");
        morning.setOccurredAt(LocalDate.of(2026, 10, 2).atTime(10, 0).atOffset(BEIJING));
        morning.setReportDeadline(morning.getOccurredAt().plusHours(24));
        morning.setDeadlineMet(true);
        AdverseEvent evening = row(AdverseEventStatus.HANDLING);
        evening.setCategory("FALL");
        evening.setSeverityClass("II");
        evening.setSeverityGrade("B");
        evening.setWardId("W02");
        evening.setOccurredAt(LocalDate.of(2026, 10, 2).atTime(18, 0).atOffset(BEIJING));
        evening.setReportDeadline(evening.getOccurredAt().plusHours(24));
        evening.setDeadlineMet(false);
        AdverseEvent night = row(AdverseEventStatus.CLOSED);
        night.setCategory("MEDICATION_ERROR");
        night.setSeverityClass("III");
        night.setSeverityGrade("A");
        night.setWardId("W01");
        night.setOccurredAt(LocalDate.of(2026, 10, 2).atTime(3, 0).atOffset(BEIJING));
        night.setReportDeadline(null);
        night.setDeadlineMet(null);
        when(mapper.selectList(any())).thenReturn(List.of(morning, evening, night));

        AdverseEventStatsVO stats = service.stats(null, null, LocalDate.of(2026, 10, 2), null);

        assertThat(stats.date()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(stats.total()).isEqualTo(3L);
        assertThat(stats.byCategory()).containsEntry("FALL", 2L).containsEntry("MEDICATION_ERROR", 1L);
        // 词表零填充断言：八类/四级/五等/三班全键在位（稳定契约形态）
        assertThat(stats.byCategory()).hasSize(8);
        assertThat(stats.bySeverityClass())
                .containsEntry("I", 1L)
                .containsEntry("II", 1L)
                .containsEntry("III", 1L)
                .hasSize(4);
        assertThat(stats.bySeverityGrade())
                .containsEntry("A", 1L)
                .containsEntry("B", 1L)
                .containsEntry("C", 1L)
                .hasSize(5);
        assertThat(stats.byWard())
                .containsEntry("W01", 2L)
                .containsEntry("W02", 1L)
                .hasSize(2);
        // 班次时段维度（08-16 DAY/16-24 EVENING/0-8 NIGHT——与执行工作台班次映射同源）
        assertThat(stats.byShift())
                .containsEntry("DAY", 1L)
                .containsEntry("EVENING", 1L)
                .containsEntry("NIGHT", 1L)
                .hasSize(3);
        // I/II 级时限合规面：分母=2（I/II 行）、分子=1（按时上报行——流程改进面）
        assertThat(stats.deadlineTotal()).isEqualTo(2L);
        assertThat(stats.deadlineMetCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("④b stats 词表守卫：类别 code 词表外 NS-1019 显式拒绝")
    void statsRejectsInvalidCategory() {
        assertThatThrownBy(() -> service.stats("FOO", null, null, null))
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("⑤ tick 超时提醒扫描：命中返回行数、按病区聚合发布 ADVERSE_EVENT_REMIND 帧且零写操作（不改状态——只读红线）")
    void scanRemindsOverdueWithoutAnyWrite() {
        AdverseEvent overdue = row(AdverseEventStatus.REPORTED);
        overdue.setSeverityClass("I");
        AdverseEvent overdue2 = row(AdverseEventStatus.REPORTED);
        overdue2.setSeverityClass("II");
        overdue2.setEventNo("AE2026100200002");
        when(mapper.selectList(any())).thenReturn(List.of(overdue, overdue2));

        int count = service.scanAndRemindOverdue();

        assertThat(count).isEqualTo(2);
        // 大屏提醒帧（Task 11 接线）：同病区聚合一帧（wardId/超时行数/样例事件号有界 5 条）
        org.mockito.ArgumentCaptor<NurseBoardPushEvent> pushCaptor =
                org.mockito.ArgumentCaptor.forClass(NurseBoardPushEvent.class);
        verify(events).publishEvent(pushCaptor.capture());
        NurseBoardPushEvent push = pushCaptor.getValue();
        assertThat(push.wardId()).isEqualTo(WARD);
        assertThat(push.type()).isEqualTo(NurseBoardPushFrame.TYPE_ADVERSE_EVENT_REMIND);
        NurseBoardPushFrame.AdverseEventRemindPayload payload =
                (NurseBoardPushFrame.AdverseEventRemindPayload) push.payload();
        assertThat(payload.overdueCount()).isEqualTo(2);
        assertThat(payload.sampleEventNos()).containsExactly(AE_NO, "AE2026100200002");
        // 只读红线：无任何状态迁移/写操作（非惩罚——超时仅提醒）
        verify(mapper, never()).casHandle(any(), anyLong(), any(), any(), any());
        verify(mapper, never()).casClose(any(), any(), any(), any());
        verify(mapper, never()).casReturn(any(), any(), any());
        verify(mapper, never()).insert(any(AdverseEvent.class));
        verify(mapper, never()).updateById(any(AdverseEvent.class));
    }

    @Test
    @DisplayName("⑤b tick 空扫描：零命中返回 0（空 tick 幂等忽略）")
    void scanZeroHitReturnsZero() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThat(service.scanAndRemindOverdue()).isZero();
    }

    @Test
    @DisplayName("非惩罚红线：VO 与统计出参零 reporter 字段（反射组件名锚定——Spec 文化红线可执行化）")
    void nonPunitiveOutputsExcludeReporterFields() {
        List<String> voFields = Arrays.stream(AdverseEventVO.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        // 出参零惩罚字段：上报人身份禁入出参（isAnonymous 通道标识与 deadlineMet 时限合规为流程改进面，非个人面）
        assertThat(voFields).as("AdverseEventVO 禁含 reporter 组件").noneMatch(name -> name.toLowerCase()
                .contains("reporter"));
        List<String> statsFields = Arrays.stream(AdverseEventStatsVO.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(statsFields).as("AdverseEventStatsVO 禁含 reporter 组件").noneMatch(name -> name.toLowerCase()
                .contains("reporter"));
        // 统计聚合纯计数：无任何个人身份维度键
        assertThat(statsFields).allMatch(name -> !name.toLowerCase().contains("handler"));
    }

    @Test
    @DisplayName("上报守卫：词表外值三级 NS-1019；occurredAt 超容差未来时刻 NS-1016")
    void reportRejectsInvalidVocabAndFutureOccurredAt() {
        OffsetDateTime occurredAt = OffsetDateTime.now(BEIJING).minusHours(1);
        assertThatThrownBy(() -> service.report(report("FOO", "I", "A", occurredAt, REPORTER, false)))
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        assertThatThrownBy(() -> service.report(report("FALL", "V", "A", occurredAt, REPORTER, false)))
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        assertThatThrownBy(() -> service.report(report("FALL", "I", "F", occurredAt, REPORTER, false)))
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        // 未来时刻倒灌守卫（NursingAssessmentServiceImpl 同款先例+5 分钟容差）：超容差拒绝
        assertThatThrownBy(() -> service.report(
                        report("FALL", "I", "A", OffsetDateTime.now(BEIJING).plusMinutes(10), REPORTER, false)))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT));
        verifyNoInteractions(seqGate, events);
    }

    @Test
    @DisplayName("list 分页：发生时点降序+VO 映射+分页口径直出；词表外过滤值 NS-1019")
    void listPagesAndRejectsInvalidVocab() {
        Page<AdverseEvent> pageResult = new Page<>(0, 20);
        pageResult.setRecords(List.of(row(AdverseEventStatus.REPORTED)));
        pageResult.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(pageResult);

        PageResult<AdverseEventVO> vo = service.list(
                "FALL", WARD, AdverseEventStatus.REPORTED.getCode(), LocalDate.of(2026, 10, 2), 0, 20, null);

        assertThat(vo.content()).hasSize(1);
        assertThat(vo.content().get(0).eventNo()).isEqualTo(AE_NO);
        assertThat(vo.total()).isEqualTo(1L);
        // 词表守卫：类别/状态词表外值显式拒绝（NS-1019）
        assertThatThrownBy(() -> service.list("FOO", null, null, null, 0, 20, null))
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        assertThatThrownBy(() -> service.list(null, null, "FOO", null, 0, 20, null))
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
    }

    @Test
    @DisplayName("W-40 list 携 wardId：按单值等值过滤且不叠加绑定集 in（wardScope=null 不过滤）")
    void listWithWardIdFiltersBySingleValueWithoutScopeIn() {
        Page<AdverseEvent> pageResult = new Page<>(0, 20);
        pageResult.setRecords(List.of(row(AdverseEventStatus.REPORTED)));
        pageResult.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(pageResult);

        service.list(null, WARD, null, null, 0, 20, null);

        // 查询 SQL 守卫：ward_id 等值参数在位且无 IN 段（守卫已校验归属，单病区视角零绑定集叠加）
        ArgumentCaptor<Wrapper<AdverseEvent>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(), wrapperCaptor.capture());
        LambdaQueryWrapper<AdverseEvent> wrapper = (LambdaQueryWrapper<AdverseEvent>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("ward_id").doesNotContain("IN");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(WARD);
    }

    @Test
    @DisplayName("W-40 list 无 wardId：按当班绑定集 in 过滤（wardScope 逐值入参）")
    void listWithoutWardIdFiltersByScopeIn() {
        Page<AdverseEvent> pageResult = new Page<>(0, 20);
        pageResult.setRecords(List.of());
        pageResult.setTotal(0);
        when(mapper.selectPage(any(), any())).thenReturn(pageResult);

        service.list(null, null, null, null, 0, 20, List.of("W01", "W02"));

        // 查询 SQL 守卫：ward_id IN 段生成且绑定集两病区逐值入参
        ArgumentCaptor<Wrapper<AdverseEvent>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(), wrapperCaptor.capture());
        LambdaQueryWrapper<AdverseEvent> wrapper = (LambdaQueryWrapper<AdverseEvent>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("ward_id IN");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("W01", "W02");
    }

    @Test
    @DisplayName("W-40 list 空绑定集：直接返回空页零 DB 交互（fail-closed 防御——无可见病区不放大查询）")
    void listWithEmptyScopeShortCircuitsToEmptyPage() {
        PageResult<AdverseEventVO> vo = service.list(null, null, null, null, 0, 20, List.of());

        assertThat(vo.content()).isEmpty();
        assertThat(vo.total()).isZero();
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("W-40 stats 无 wardId：按当班绑定集 in 过滤（wardScope 逐值入参）")
    void statsWithoutWardIdFiltersByScopeIn() {
        when(mapper.selectList(any())).thenReturn(List.of(row(AdverseEventStatus.REPORTED)));

        service.stats(null, null, LocalDate.of(2026, 10, 2), List.of("W01"));

        ArgumentCaptor<Wrapper<AdverseEvent>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<AdverseEvent> wrapper = (LambdaQueryWrapper<AdverseEvent>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("ward_id IN");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("W01");
    }

    @Test
    @DisplayName("W-40 stats 空绑定集：零填充空统计零 DB 交互（词表维度稳定契约不破）")
    void statsWithEmptyScopeShortCircuitsToZeroFilledStats() {
        AdverseEventStatsVO vo = service.stats(null, null, null, List.of());

        assertThat(vo.total()).isZero();
        assertThat(vo.byCategory()).as("八类词表零填充契约保持").hasSize(8);
        assertThat(vo.byCategory().values()).allMatch(v -> v == 0L);
        assertThat(vo.byShift()).hasSize(3);
        verifyNoInteractions(mapper);
    }

    // ===================== 构造辅助 =====================

    /**
     * 上报入参构造辅助（词表/时点/匿名面按用例定制）。
     *
     * @param category    事件类别 code
     * @param severityClass 分级 code
     * @param severityGrade 等级 code
     * @param occurredAt  发生时点
     * @param reporterId  上报人（可空=匿名面）
     * @param anonymous   匿名标识（可空）
     * @return 上报入参
     */
    private static AdverseEventReportRequest report(
            String category,
            String severityClass,
            String severityGrade,
            OffsetDateTime occurredAt,
            Long reporterId,
            Boolean anonymous) {
        return new AdverseEventReportRequest(
                category,
                severityClass,
                severityGrade,
                WARD,
                "I2026100200001",
                9001L,
                occurredAt,
                "患者床旁跌倒事件经过",
                null,
                reporterId,
                anonymous);
    }

    /**
     * 上报入参快捷构造（W-72 归属锁定用例）：词表/时点取用例基准值，身份面（reporterId/
     * isAnonymous）按用例定制——请求体身份差异值与令牌分置表达的入口。
     *
     * @param reporterId 上报人请求体值（差异值载体，可空）
     * @param anonymous  匿名标识（显式 true 走匿名通道）
     * @return 上报入参
     */
    private static AdverseEventReportRequest reportRequest(Long reporterId, Boolean anonymous) {
        return report("FALL", "I", "C", OffsetDateTime.now(BEIJING).minusHours(1), reporterId, anonymous);
    }

    /**
     * 不良事件行构造辅助（状态面定制；号/病区/时点取用例基准）。
     *
     * @param status 处置状态
     * @return 不良事件行
     */
    private static AdverseEvent row(AdverseEventStatus status) {
        AdverseEvent row = new AdverseEvent();
        row.setId(1L);
        row.setEventNo(AE_NO);
        row.setCategory("FALL");
        row.setSeverityClass("I");
        row.setSeverityGrade("C");
        row.setWardId(WARD);
        row.setVisitId("I2026100200001");
        row.setPatientId(9001L);
        row.setOccurredAt(OffsetDateTime.now(BEIJING).minusHours(2));
        row.setEventSummary("患者床旁跌倒事件经过");
        row.setHandlingNote("");
        row.setReporterId(REPORTER);
        row.setIsAnonymous(false);
        row.setStatus(status.getCode());
        return row;
    }
}
