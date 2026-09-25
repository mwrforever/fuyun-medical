package com.fuyun.inpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.ConsultationPayload;
import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.ConsultationCreateRequest;
import com.fuyun.inpatient.dto.ConsultationOpinionRequest;
import com.fuyun.inpatient.entity.Consultation;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.enums.ConsultationStatus;
import com.fuyun.inpatient.enums.ConsultationUrgency;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.ConsultationMapper;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.vo.ConsultationVO;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Update;
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

/**
 * 会诊管理域服务单测（Task 11 brief 冻结六用例 + 守卫与 LINE=1.00 覆盖补面）：①急会诊 30min
 * 时限 ②普通 24h ③读时逾期升级一次+二次查询不重发（DB 标记防重发）④逾期后仍可接单
 * （accept 清标记）⑤意见完成闭环 ⑥consult 医嘱审核自动建会诊单（钩子断言归
 * OrderAuditServiceImplTest）；另覆盖申请守卫链（词表外/非在院/缺科室/操作者）、三 CAS 零行
 * IP-1020、取消双合法出边与 GC23 注解 SQL 锚。MP 3.5.17 单测范式：lambdaQuery 触达实体在
 * 初始化钩子中手工注册表信息。
 */
@ExtendWith(MockitoExtension.class)
class ConsultationServiceImplTest {

    /** 编排主体 I 型 14 位就诊号 */
    private static final String VISIT_ID = "I2026092500001";

    /** 就诊行主键（consultation.visit_id 消费面——非 I 型号） */
    private static final long VISIT_PK = 7001L;

    /** 患者主索引 */
    private static final long PATIENT_ID = 1001L;

    /** 操作者员工 ID（数字形态——审计口径） */
    private static final long OPERATOR = 1001L;

    /** 会诊单号（CS 段发号） */
    private static final String CONSULT_NO = "CS2026092500001";

    /** 申请科室编码（就诊当前科室——申请科室权威在库） */
    private static final String DEPT_FROM = "DEPT-INTERNAL-01";

    /** 受邀科室编码 */
    private static final String DEPT_TO = "DEPT-SURGERY-02";

    @Mock
    private ConsultationMapper consultationMapper;

    @Mock
    private InpatientVisitMapper visitMapper;

    @Mock
    private InpatientSeqGate seqGate;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<Consultation> rowCaptor;

    @Captor
    private ArgumentCaptor<InpatientDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<Consultation>> queryCaptor;

    private ConsultationServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（两实体查询面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Consultation.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InpatientVisit.class);
    }

    @BeforeEach
    void setUp() {
        service = new ConsultationServiceImpl(consultationMapper, visitMapper, seqGate, events);
        OperatorContextHolder.set(String.valueOf(OPERATOR));
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("用例①急会诊时限：URGENT 响应截止=申请时点+30min，requested 事件载荷申请态子集")
    void urgentConsultationDeadlineIsThirtyMinutes() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(seqGate.nextNo("CS")).thenReturn(CONSULT_NO);

        ConsultationVO vo =
                service.create(new ConsultationCreateRequest(VISIT_ID, DEPT_TO, "URGENT", null, "术后恢复情况会诊"));

        // 落库面：REQUESTED 态 + 申请科室取就诊 current_dept_id + 级别缺省科内 + 30min 时限
        verify(consultationMapper).insert(rowCaptor.capture());
        Consultation row = rowCaptor.getValue();
        assertThat(row.getConsultNo()).isEqualTo(CONSULT_NO);
        assertThat(row.getVisitId()).isEqualTo(VISIT_PK);
        assertThat(row.getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(row.getFromDeptId()).isEqualTo(DEPT_FROM);
        assertThat(row.getToDeptId()).isEqualTo(DEPT_TO);
        assertThat(row.getRequesterId()).isEqualTo(String.valueOf(OPERATOR));
        assertThat(row.getStatus()).isEqualTo(ConsultationStatus.REQUESTED.getCode());
        assertThat(row.getUrgency()).isEqualTo(ConsultationUrgency.URGENT.getCode());
        assertThat(row.getLevel()).isEqualTo("DEPT");
        assertThat(row.getOverdueFlag()).isFalse();
        assertThat(Duration.between(row.getRequestedAt(), row.getResponseDeadline()))
                .as("急会诊响应时限 30 分钟（调研依据 13）")
                .isEqualTo(ConsultationUrgency.URGENT.responseWindow());

        // requested 事件（V901 id 68 载荷申请态子集：consultNo~responseDeadline 与 reason）
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType())
                .isEqualTo(InpatientMessagingConstants.EVENT_CONSULTATION_REQUESTED);
        ConsultationPayload payload =
                (ConsultationPayload) eventCaptor.getValue().payload();
        assertThat(payload.consultNo()).isEqualTo(CONSULT_NO);
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.patientId()).isEqualTo(PATIENT_ID);
        assertThat(payload.fromDeptId()).isEqualTo(DEPT_FROM);
        assertThat(payload.toDeptId()).isEqualTo(DEPT_TO);
        assertThat(payload.urgency()).isEqualTo("URGENT");
        assertThat(payload.responseDeadline())
                .isEqualTo(row.getResponseDeadline().toInstant());
        assertThat(payload.acceptedAt()).isNull();
        assertThat(payload.completedAt()).isNull();
        assertThat(payload.overdueAt()).isNull();
        assertThat(payload.cancelledAt()).isNull();
        assertThat(payload.reason()).isEqualTo("术后恢复情况会诊");

        // 出参回显
        assertThat(vo.consultNo()).isEqualTo(CONSULT_NO);
        assertThat(vo.visitId()).isEqualTo(VISIT_ID);
        assertThat(vo.status()).isEqualTo(ConsultationStatus.REQUESTED.getCode());
    }

    @Test
    @DisplayName("用例②普通会诊时限：NORMAL 响应截止=申请时点+24h，显式级别 HOSPITAL 落值")
    void normalConsultationDeadlineIsTwentyFourHours() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(seqGate.nextNo("CS")).thenReturn(CONSULT_NO);

        service.create(new ConsultationCreateRequest(VISIT_ID, DEPT_TO, "NORMAL", "HOSPITAL", null));

        verify(consultationMapper).insert(rowCaptor.capture());
        Consultation row = rowCaptor.getValue();
        assertThat(row.getUrgency()).isEqualTo(ConsultationUrgency.NORMAL.getCode());
        assertThat(row.getLevel()).isEqualTo("HOSPITAL");
        assertThat(Duration.between(row.getRequestedAt(), row.getResponseDeadline()))
                .as("普通会诊响应时限 24 小时（调研依据 13）")
                .isEqualTo(ConsultationUrgency.NORMAL.responseWindow());
        assertThat(row.getReason()).isNull();
    }

    @Test
    @DisplayName("用例③读时逾期升级：越限未标记行 CAS 置位+overdue 动作事件一次，状态停留 REQUESTED")
    void overdueEscalationMarksOnceAndBroadcastsActionEvent() {
        // 越响应截止 1 小时的待响应会诊（急会诊 30min 时限超窗场景）
        Consultation overdueRow = consultRow(
                CONSULT_NO, ConsultationStatus.REQUESTED, OffsetDateTime.now().minusHours(1), false);
        Page<Consultation> page = new Page<>(1, 20);
        page.setRecords(List.of(overdueRow));
        page.setTotal(1);
        when(consultationMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(visitMapper.selectByIds(any())).thenReturn(List.of(visitRow()));
        when(consultationMapper.casMarkOverdue(eq(CONSULT_NO), eq(String.valueOf(OPERATOR))))
                .thenReturn(1);

        PageResult<ConsultationVO> result = service.list(null, null, 0, 20);

        // 逾期标记置位回显 + 动作事件（V901 id 71 超时态子集——overdueAt 取用，状态不迁移）
        assertThat(result.total()).isEqualTo(1);
        ConsultationVO vo = result.content().get(0);
        assertThat(vo.overdueFlag()).isTrue();
        assertThat(vo.status()).isEqualTo(ConsultationStatus.REQUESTED.getCode());
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType())
                .isEqualTo(InpatientMessagingConstants.EVENT_CONSULTATION_OVERDUE);
        ConsultationPayload payload =
                (ConsultationPayload) eventCaptor.getValue().payload();
        assertThat(payload.consultNo()).isEqualTo(CONSULT_NO);
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.toDeptId()).isEqualTo(DEPT_TO);
        assertThat(payload.overdueAt()).isNotNull();
        assertThat(payload.acceptedAt()).isNull();
        assertThat(payload.completedAt()).isNull();
        assertThat(payload.cancelledAt()).isNull();

        // 列表查询过滤锚：全状态无科室过滤（默认分支）+ 申请时点升序 FIFO
        verify(consultationMapper).selectPage(any(Page.class), queryCaptor.capture());
        LambdaQueryWrapper<Consultation> wrapper = rendered(queryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs()).doesNotContainValue(ConsultationStatus.REQUESTED.getCode());
        assertThat(wrapper.getSqlSegment()).contains("ORDER BY requested_at");
    }

    @Test
    @DisplayName("用例③二次查询不重发：DB 标记已置位行跳过判定——零 CAS 零事件（防重发红线）")
    void overdueEscalationSecondReadDoesNotResend() {
        // 已标记行（二次查询快照）+未越限行+已接单行——三行均不进升级面
        Consultation flagged = consultRow(
                "CS2026092500002",
                ConsultationStatus.REQUESTED,
                OffsetDateTime.now().minusHours(2),
                true);
        Consultation future = consultRow(
                "CS2026092500003",
                ConsultationStatus.REQUESTED,
                OffsetDateTime.now().plusHours(1),
                false);
        Consultation accepted = consultRow(
                "CS2026092500004",
                ConsultationStatus.ACCEPTED,
                OffsetDateTime.now().minusHours(2),
                false);
        Page<Consultation> page = new Page<>(1, 20);
        page.setRecords(List.of(flagged, future, accepted));
        page.setTotal(3);
        when(consultationMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(visitMapper.selectByIds(any())).thenReturn(List.of(visitRow()));

        PageResult<ConsultationVO> result = service.list(ConsultationStatus.REQUESTED, DEPT_TO, 0, 20);

        // DB 标记防重发：已标记/未越限/已流转三态均零 CAS——overdue 事件全链零重发
        verify(consultationMapper, never()).casMarkOverdue(any(), any());
        verifyNoInteractions(events);
        assertThat(result.total()).isEqualTo(3);

        // 过滤锚：状态等值 + 科室双侧命中（申请/受邀任一侧 OR 语义）
        verify(consultationMapper).selectPage(any(Page.class), queryCaptor.capture());
        LambdaQueryWrapper<Consultation> wrapper = rendered(queryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values()).contains(ConsultationStatus.REQUESTED.getCode(), DEPT_TO);
        assertThat(wrapper.getSqlSegment()).contains(" OR ");
    }

    @Test
    @DisplayName("用例③补面·并发窗口：CAS 零行（他节点已置位）不重发事件，标记保持未置回显")
    void overdueCasZeroRowDoesNotBroadcast() {
        Consultation concurrent = consultRow(
                CONSULT_NO, ConsultationStatus.REQUESTED, OffsetDateTime.now().minusMinutes(45), false);
        Page<Consultation> page = new Page<>(1, 20);
        page.setRecords(List.of(concurrent));
        page.setTotal(1);
        when(consultationMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(visitMapper.selectByIds(any())).thenReturn(List.of(visitRow()));
        when(consultationMapper.casMarkOverdue(eq(CONSULT_NO), eq(String.valueOf(OPERATOR))))
                .thenReturn(0);

        PageResult<ConsultationVO> result = service.list(null, null, 0, 20);

        // 旧值限定 CAS 零行=他节点抢先置位——本读零事件零置位（防重发双保险）
        verifyNoInteractions(events);
        assertThat(result.content().get(0).overdueFlag()).isFalse();
    }

    @Test
    @DisplayName("空页直过：零行零就诊映射零升级扫描（visitNosOf 空集短路面）")
    void emptyPageSkipsVisitMappingAndSweep() {
        Page<Consultation> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(consultationMapper.selectPage(any(Page.class), any())).thenReturn(page);

        PageResult<ConsultationVO> result = service.list(null, null, 0, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.total()).isZero();
        // 空集短路：就诊号批量映射零触达（in 批量禁空集）
        verify(visitMapper, never()).selectByIds(any());
    }

    @Test
    @DisplayName("用例④逾期后仍可接单：REQUESTED→ACCEPTED CAS 清 overdue_flag+accepted 事件+GC23 SQL 锚")
    void acceptClearsOverdueFlagAndPublishesAccepted() {
        // 已逾期升级的待响应会诊（标记 true）——超时为动作非状态迁移，接单仍可达
        Consultation requested = consultRow(
                CONSULT_NO, ConsultationStatus.REQUESTED, OffsetDateTime.now().minusMinutes(40), true);
        Consultation accepted = consultRow(
                CONSULT_NO, ConsultationStatus.ACCEPTED, OffsetDateTime.now().minusMinutes(40), false);
        accepted.setResponseTime(OffsetDateTime.now());
        when(consultationMapper.selectOne(any())).thenReturn(requested, accepted);
        when(consultationMapper.casAccept(CONSULT_NO, String.valueOf(OPERATOR))).thenReturn(1);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());

        ConsultationVO vo = service.accept(CONSULT_NO);

        // 接单 CAS 命中：接单时点由库端 now() 回读（事件载荷源）+ 逾期标记已清
        verify(consultationMapper).casAccept(CONSULT_NO, String.valueOf(OPERATOR));
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType())
                .isEqualTo(InpatientMessagingConstants.EVENT_CONSULTATION_ACCEPTED);
        ConsultationPayload payload =
                (ConsultationPayload) eventCaptor.getValue().payload();
        assertThat(payload.consultNo()).isEqualTo(CONSULT_NO);
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.toDeptId()).isEqualTo(DEPT_TO);
        assertThat(payload.acceptedAt()).isEqualTo(accepted.getResponseTime().toInstant());
        assertThat(payload.completedAt()).isNull();
        assertThat(vo.status()).isEqualTo(ConsultationStatus.ACCEPTED.getCode());
        assertThat(vo.responseTime()).isEqualTo(accepted.getResponseTime());
        assertThat(vo.overdueFlag()).isFalse();

        // GC23 锚：接单条件更新为 @Update 注解 SQL——REQUESTED 限定+清标记+显式 deleted=0
        assertThat(recordSql("casAccept", String.class, String.class))
                .contains("status = 'ACCEPTED'")
                .contains("overdue_flag = FALSE")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("用例⑤意见完成闭环：ACCEPTED→COMPLETED CAS 意见归档+completed 事件+GC23 SQL 锚")
    void opinionCompletesConsultationAndArchivesOpinion() {
        OffsetDateTime consultTime = OffsetDateTime.now();
        Consultation accepted = consultRow(
                CONSULT_NO, ConsultationStatus.ACCEPTED, OffsetDateTime.now().minusHours(3), false);
        Consultation completed = consultRow(
                CONSULT_NO, ConsultationStatus.COMPLETED, OffsetDateTime.now().minusHours(3), false);
        completed.setConsultTime(consultTime);
        completed.setOpinion("建议转外科手术治疗");
        when(consultationMapper.selectOne(any())).thenReturn(accepted, completed);
        when(consultationMapper.casComplete(eq(CONSULT_NO), eq("建议转外科手术治疗"), eq(String.valueOf(OPERATOR))))
                .thenReturn(1);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());

        ConsultationVO vo = service.opinion(CONSULT_NO, new ConsultationOpinionRequest("建议转外科手术治疗"));

        // 完成 CAS 命中：完成时点由库端 now() 回读 + 意见同语句归档（M09 引用取数面）
        verify(consultationMapper).casComplete(eq(CONSULT_NO), eq("建议转外科手术治疗"), eq(String.valueOf(OPERATOR)));
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType())
                .isEqualTo(InpatientMessagingConstants.EVENT_CONSULTATION_COMPLETED);
        ConsultationPayload payload =
                (ConsultationPayload) eventCaptor.getValue().payload();
        assertThat(payload.completedAt()).isEqualTo(consultTime.toInstant());
        assertThat(payload.acceptedAt()).isNull();
        assertThat(vo.status()).isEqualTo(ConsultationStatus.COMPLETED.getCode());
        assertThat(vo.opinion()).isEqualTo("建议转外科手术治疗");
        assertThat(vo.consultTime()).isEqualTo(consultTime);

        // GC23 锚：意见完成条件更新为 @Update 注解 SQL——ACCEPTED 限定+意见归档+显式 deleted=0
        assertThat(recordSql("casComplete", String.class, String.class, String.class))
                .contains("status = 'COMPLETED'")
                .contains("opinion = #{opinion}")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("用例⑤补面·取消：REQUESTED→CANCELLED 终态+cancelled 事件（reason 取单面申请原因）")
    void cancelFromRequestedPublishesCancelled() {
        Consultation requested = consultRow(
                CONSULT_NO, ConsultationStatus.REQUESTED, OffsetDateTime.now().minusMinutes(10), false);
        requested.setReason("患者病情变化暂不会诊");
        when(consultationMapper.selectOne(any())).thenReturn(requested);
        when(consultationMapper.casCancel(CONSULT_NO, String.valueOf(OPERATOR))).thenReturn(1);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());

        ConsultationVO vo = service.cancel(CONSULT_NO);

        // 取消事件（V901 id 72 载荷取消态子集：cancelledAt 与 reason；取消无独立入参——reason 取单面）
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType())
                .isEqualTo(InpatientMessagingConstants.EVENT_CONSULTATION_CANCELLED);
        ConsultationPayload payload =
                (ConsultationPayload) eventCaptor.getValue().payload();
        assertThat(payload.cancelledAt()).isNotNull();
        assertThat(payload.reason()).isEqualTo("患者病情变化暂不会诊");
        assertThat(payload.overdueAt()).isNull();
        assertThat(vo.status()).isEqualTo(ConsultationStatus.CANCELLED.getCode());

        // GC23 锚：取消条件更新为 @Update 注解 SQL——双合法出边（REQUESTED/ACCEPTED IN）+显式 deleted=0
        assertThat(recordSql("casCancel", String.class, String.class))
                .contains("status = 'CANCELLED'")
                .contains("status IN ('REQUESTED','ACCEPTED')")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("守卫面·接单 CAS 零行：非 REQUESTED 态（已接单/取消/完成）定性 IP-1020")
    void acceptOnNonRequestedStateRejected() {
        Consultation completed = consultRow(
                CONSULT_NO, ConsultationStatus.COMPLETED, OffsetDateTime.now().minusHours(5), false);
        when(consultationMapper.selectOne(any())).thenReturn(completed);
        when(consultationMapper.casAccept(CONSULT_NO, String.valueOf(OPERATOR))).thenReturn(0);

        assertThatThrownBy(() -> service.accept(CONSULT_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.CONSULTATION_STATE_NOT_ALLOWED))
                .hasMessageContaining("会诊单状态不允许接单");
    }

    @Test
    @DisplayName("守卫面·意见提交 CAS 零行：非 ACCEPTED 态定性 IP-1020")
    void opinionOnNonAcceptedStateRejected() {
        Consultation requested = consultRow(
                CONSULT_NO, ConsultationStatus.REQUESTED, OffsetDateTime.now().minusMinutes(5), false);
        when(consultationMapper.selectOne(any())).thenReturn(requested);
        when(consultationMapper.casComplete(any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.opinion(CONSULT_NO, new ConsultationOpinionRequest("意见")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.CONSULTATION_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("守卫面·取消 CAS 零行：终态不可取消定性 IP-1020")
    void cancelOnTerminalStateRejected() {
        Consultation completed = consultRow(
                CONSULT_NO, ConsultationStatus.COMPLETED, OffsetDateTime.now().minusHours(5), false);
        when(consultationMapper.selectOne(any())).thenReturn(completed);
        when(consultationMapper.casCancel(CONSULT_NO, String.valueOf(OPERATOR))).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(CONSULT_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.CONSULTATION_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("守卫面·会诊单不存在：IP-1019 404")
    void acceptUnknownConsultNoThrowsNotFound() {
        when(consultationMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.accept("CS2026092599999"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.CONSULTATION_NOT_FOUND));
    }

    @Test
    @DisplayName("守卫面·接单回读就诊缺失：IP-1007 数据不一致 fail-closed")
    void acceptWithMissingVisitThrowsDataInconsistency() {
        Consultation requested = consultRow(
                CONSULT_NO, ConsultationStatus.REQUESTED, OffsetDateTime.now().minusMinutes(5), false);
        Consultation accepted = consultRow(
                CONSULT_NO, ConsultationStatus.ACCEPTED, OffsetDateTime.now().minusMinutes(5), false);
        accepted.setResponseTime(OffsetDateTime.now());
        when(consultationMapper.selectOne(any())).thenReturn(requested, accepted);
        when(consultationMapper.casAccept(CONSULT_NO, String.valueOf(OPERATOR))).thenReturn(1);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);

        assertThatThrownBy(() -> service.accept(CONSULT_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));
    }

    @Test
    @DisplayName("守卫面·申请链四连：紧急程度词表外 IP-1022")
    void createWithUnknownUrgencyRejected() {
        assertThatThrownBy(() -> service.create(new ConsultationCreateRequest(VISIT_ID, DEPT_TO, "STAT", null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        verifyNoInteractions(visitMapper, seqGate, events);
    }

    @Test
    @DisplayName("守卫面·申请链：会诊级别词表外 IP-1022")
    void createWithUnknownLevelRejected() {
        assertThatThrownBy(
                        () -> service.create(new ConsultationCreateRequest(VISIT_ID, DEPT_TO, "URGENT", "WARD", null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        verifyNoInteractions(visitMapper, seqGate, events);
    }

    @Test
    @DisplayName("守卫面·申请链：就诊不存在 IP-1007")
    void createWithUnknownVisitRejected() {
        when(visitMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.create(new ConsultationCreateRequest(VISIT_ID, DEPT_TO, "URGENT", null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));
        verifyNoInteractions(seqGate, events);
    }

    @Test
    @DisplayName("守卫面·申请链：非在院就诊（未入科/已出院）禁申请 IP-1008")
    void createOnNonAdmittedVisitRejected() {
        InpatientVisit registered = visitRow();
        registered.setStatus(VisitStatus.REGISTERED.getCode());
        when(visitMapper.selectOne(any())).thenReturn(registered);

        assertThatThrownBy(() -> service.create(new ConsultationCreateRequest(VISIT_ID, DEPT_TO, "URGENT", null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.VISIT_STATE_NOT_ALLOWED));
        verifyNoInteractions(seqGate, events);
    }

    @Test
    @DisplayName("守卫面·申请链：在院就诊缺当前科室（数据不一致）IP-1023")
    void createOnAdmittedVisitWithoutDeptRejected() {
        InpatientVisit noDept = visitRow();
        noDept.setCurrentDeptId(null);
        when(visitMapper.selectOne(any())).thenReturn(noDept);

        assertThatThrownBy(() -> service.create(new ConsultationCreateRequest(VISIT_ID, DEPT_TO, "URGENT", null, null)))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
        verifyNoInteractions(seqGate, events);
    }

    @Test
    @DisplayName("守卫面·申请链：操作者标识缺失（无登录上下文）IP-1022")
    void createWithoutOperatorContextRejected() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        OperatorContextHolder.clear();

        assertThatThrownBy(() -> service.create(new ConsultationCreateRequest(VISIT_ID, DEPT_TO, "URGENT", null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        verifyNoInteractions(seqGate, events);
    }

    @Test
    @DisplayName("守卫面·申请链：操作者标识非数字 IP-1022（工号脱敏出文案）")
    void createWithNonNumericOperatorRejected() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        OperatorContextHolder.set("doc-01");

        assertThatThrownBy(() -> service.create(new ConsultationCreateRequest(VISIT_ID, DEPT_TO, "URGENT", null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID);
                    // 等保三级口径：非数字工号脱敏留痕（首尾各留 1 位）
                    assertThat(e.getMessage()).contains("d***1");
                });
        verifyNoInteractions(seqGate, events);
    }

    @Test
    @DisplayName("读路径降级面：无登录上下文置标记操作者回退 system（CAS 参数兜底）")
    void overdueSweepFallsBackToSystemOperatorWithoutContext() {
        Consultation overdueRow = consultRow(
                CONSULT_NO, ConsultationStatus.REQUESTED, OffsetDateTime.now().minusHours(1), false);
        Page<Consultation> page = new Page<>(1, 20);
        page.setRecords(List.of(overdueRow));
        page.setTotal(1);
        when(consultationMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(visitMapper.selectByIds(any())).thenReturn(List.of(visitRow()));
        when(consultationMapper.casMarkOverdue(CONSULT_NO, "system")).thenReturn(1);
        OperatorContextHolder.clear();

        // 消费线程/无上下文读路径：置标记审计列回退 system（与审计列默认同源），升级链不因上下文缺失中断
        assertThatCode(() -> service.list(null, null, 0, 20)).doesNotThrowAnyException();

        verify(consultationMapper).casMarkOverdue(CONSULT_NO, "system");
    }

    /**
     * 构造会诊行（列表升级与状态迁移用例载体）：单号/状态/响应截止/逾期标记四元组，申请面
     * 默认申请科室=DEPT_FROM、受邀科室=DEPT_TO（载荷断言依赖）。
     */
    private static Consultation consultRow(
            String consultNo, ConsultationStatus status, OffsetDateTime deadline, boolean overdueFlag) {
        Consultation row = new Consultation();
        row.setId(8001L);
        row.setConsultNo(consultNo);
        row.setVisitId(VISIT_PK);
        row.setPatientId(PATIENT_ID);
        row.setFromDeptId(DEPT_FROM);
        row.setToDeptId(DEPT_TO);
        row.setLevel("DEPT");
        row.setUrgency("URGENT");
        row.setRequestedAt(deadline.minusMinutes(30));
        row.setResponseDeadline(deadline);
        row.setOverdueFlag(overdueFlag);
        row.setStatus(status.getCode());
        return row;
    }

    /** 构造在院就诊行（申请守卫与 I 型号映射载体）。 */
    private static InpatientVisit visitRow() {
        InpatientVisit row = new InpatientVisit();
        row.setId(VISIT_PK);
        row.setVisitId(VISIT_ID);
        row.setPatientId(PATIENT_ID);
        row.setCurrentDeptId(DEPT_FROM);
        row.setStatus(VisitStatus.ADMITTED.getCode());
        return row;
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表）。 */
    @SuppressWarnings("unchecked")
    private static LambdaQueryWrapper<Consultation> rendered(Wrapper<Consultation> captured) {
        LambdaQueryWrapper<Consultation> wrapper = (LambdaQueryWrapper<Consultation>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }

    /**
     * 读mapper方法 @Update 注解 SQL 拼接原文（GC23 注解 SQL 锚断言载体）。
     *
     * @param method     mapper 方法名，非空
     * @param paramTypes 方法参数类型清单，非空
     * @return 注解 SQL 拼接文本
     */
    private static String recordSql(String method, Class<?>... paramTypes) {
        try {
            Update update =
                    ConsultationMapper.class.getMethod(method, paramTypes).getAnnotation(Update.class);
            assertThat(update).as("条件更新必须为 @Update 注解 SQL（GC23）").isNotNull();
            return String.join("", update.value());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("mapper 方法不存在：" + method, e);
        }
    }
}
