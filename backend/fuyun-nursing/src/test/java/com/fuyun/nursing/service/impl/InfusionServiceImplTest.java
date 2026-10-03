package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.InfusionStartedPayload;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.entity.InfusionMonitorLink;
import com.fuyun.nursing.entity.OrderExecution;
import com.fuyun.nursing.enums.ExecutionStatus;
import com.fuyun.nursing.enums.ExecutionType;
import com.fuyun.nursing.internal.NurseBoardPushEvent;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.InfusionMonitorLinkMapper;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.mapper.OrderExecutionMapper;
import com.fuyun.nursing.vo.ActiveInfusionVO;
import com.fuyun.nursing.vo.NurseBoardPushFrame;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

/**
 * 输液闭环域服务单测（Task 6 brief 用例组）：start 建链/激活+infusion.started 事件（五字段、
 * deviceId 不进事件）、在途清单聚合（MONITORING 过滤）、告警升级挂单三路（triggered 直配/
 * escalated 反查累计/closed 复位）、挂接 fail-closed NS-1024 两分支。MP 3.5.17 单测范式：
 * lambdaQuery 触达实体 @BeforeAll 手工注册表信息。JaCoCo nursing.service.impl 1.00 行覆盖
 * 红线：本类承载 InfusionServiceImpl 全部分支面。
 */
@ExtendWith(MockitoExtension.class)
class InfusionServiceImplTest {

    /** 执行单号（业务号断言基准） */
    private static final String EXEC = "EX2026100200001";

    /** 第二执行单号（在途清单多行断言基准） */
    private static final String EXEC_TWO = "EX2026100200002";

    /** I 型 14 位合法 visit_id */
    private static final String VISIT = "I2026100200001";

    /** 病区编码 */
    private static final String WARD = "W01";

    /** 袋签码（PIVAS 摆药贴签溯源——建链承载值） */
    private static final String BAG_LABEL = "BAG2026100201";

    /** 告警业务号（升级挂单锚） */
    private static final String ALARM_NO = "AL2026100200001";

    /** 患者主索引 */
    private static final long PATIENT = 7L;

    /** 输液泵设备标识（PDA 扫码回填面） */
    private static final String PUMP = "PUMP-01";

    @Mock
    private OrderExecutionMapper executionMapper;

    @Mock
    private InfusionMonitorLinkMapper monitorLinkMapper;

    @Mock
    private NursingTaskMapper taskMapper;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<Object> eventCaptor;

    private InfusionServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（主表+挂接）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderExecution.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), InfusionMonitorLink.class);
    }

    @BeforeEach
    void setUp() {
        service = new InfusionServiceImpl(executionMapper, monitorLinkMapper, taskMapper, events);
        // REST/MQ 链路操作者上下文（审计列落位口径）
        OperatorContextHolder.set("1001");
    }

    @AfterEach
    void clearContext() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("① start 建链：MONITORING 挂接激活（started_at 刷新+设备回填）+infusion.started 五字段事件")
    void startInfusionActivatesLinkAndPublishesEvent() {
        OrderExecution execution = executingInfusion();
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        execution.setStartedAt(startedAt);
        when(monitorLinkMapper.selectOne(any())).thenReturn(monitoringLink());
        when(monitorLinkMapper.casActivate(eq(EXEC), eq(startedAt), eq(PUMP), any()))
                .thenReturn(1);

        service.startInfusion(execution, PUMP);

        // 激活 CAS：started_at 刷新为真实开始输注时点，deviceId 回填
        verify(monitorLinkMapper).casActivate(eq(EXEC), eq(startedAt), eq(PUMP), any());
        // 事务内发布开始输注事件（id 62 五字段——deviceId 不进事件契约）
        verify(events).publishEvent(eventCaptor.capture());
        NursingDomainEvent event = (NursingDomainEvent) eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(NursingMessagingConstants.EVENT_INFUSION_STARTED);
        InfusionStartedPayload payload = (InfusionStartedPayload) event.payload();
        assertThat(payload.executionNo()).isEqualTo(EXEC);
        assertThat(payload.patientId()).isEqualTo(PATIENT);
        assertThat(payload.visitId()).isEqualTo(VISIT);
        assertThat(payload.bagLabelCode()).isEqualTo(BAG_LABEL);
        assertThat(payload.startedAt()).isEqualTo(startedAt.toInstant());
    }

    @Test
    @DisplayName("①b start 挂接缺行：NS-1024 fail-closed（建链唯一正规入口=摆药签收 PIVAS 升格）")
    void startInfusionRejectsMissingLink() {
        when(monitorLinkMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.startInfusion(executingInfusion(), PUMP))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.INFUSION_NOT_ACTIVE));
        verify(monitorLinkMapper, never()).casActivate(any(), any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("①c start 挂接非 MONITORING：激活 CAS 零行 NS-1024（已收口/已释放链路拒接）")
    void startInfusionRejectsNonMonitoringLink() {
        when(monitorLinkMapper.selectOne(any())).thenReturn(endedLink());
        when(monitorLinkMapper.casActivate(eq(EXEC), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.startInfusion(executingInfusion(), PUMP))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.INFUSION_NOT_ACTIVE));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("在途清单：MONITORING 过滤聚合（ENDED/缺挂接行排除）；wardId 缺失 NS-1019；空在途空清单")
    void listActiveFiltersMonitoringLinksAndGuardsWard() {
        // wardId 缺失守卫
        assertThatThrownBy(() -> service.listActive(" "))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));

        // 空在途：直出空清单不触挂接查询
        when(executionMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listActive(WARD)).isEmpty();
        verify(monitorLinkMapper, never()).selectList(any());

        // 两行在途：一行 MONITORING 聚合直出，一行挂接 ENDED 排除，一行缺挂接排除
        OrderExecution monitored = executingInfusion();
        OrderExecution ended = executingInfusion();
        ended.setExecutionNo(EXEC_TWO);
        OrderExecution unlinked = executingInfusion();
        unlinked.setExecutionNo("EX2026100200003");
        when(executionMapper.selectList(any())).thenReturn(List.of(monitored, ended, unlinked));
        InfusionMonitorLink endedLinkRow = endedLink();
        endedLinkRow.setExecutionNo(EXEC_TWO);
        when(monitorLinkMapper.selectList(any())).thenReturn(List.of(monitoringLink(), endedLinkRow));

        List<ActiveInfusionVO> active = service.listActive(WARD);

        assertThat(active).hasSize(1);
        assertThat(active.get(0).executionNo()).isEqualTo(EXEC);
        assertThat(active.get(0).bagLabelCode()).isEqualTo(BAG_LABEL);
        assertThat(active.get(0).iotDeviceId()).isEqualTo(PUMP);
        assertThat(active.get(0).latestAlarmNo()).isEqualTo(ALARM_NO);
        assertThat(active.get(0).escalationCount()).isZero();
    }

    @Test
    @DisplayName("②告警 triggered 匹配升级：patientId 直配+MONITORING 过滤——CAS 累计+挂接锚双落+任务上调")
    void escalateOnAlarmTriggeredMatchesAndEscalates() {
        when(executionMapper.selectList(any())).thenReturn(List.of(executingInfusion()));
        when(monitorLinkMapper.selectList(any())).thenReturn(List.of(monitoringLink()));
        when(executionMapper.casEscalateAlarm(eq(EXEC), eq(ALARM_NO), any())).thenReturn(1);
        when(taskMapper.casEscalatePriorityBySourceRef(eq(ALARM_NO), any())).thenReturn(1);

        int escalated = service.escalateOnAlarmTriggered(PATIENT, ALARM_NO);

        assertThat(escalated).isEqualTo(1);
        // 升级挂单 CAS（escalation_count+1+latest_alarm_no）+ 挂接行锚刷新 + 挂接任务上调
        verify(executionMapper).casEscalateAlarm(eq(EXEC), eq(ALARM_NO), any());
        verify(monitorLinkMapper).casMarkAlarm(eq(EXEC), eq(ALARM_NO), any());
        verify(taskMapper).casEscalatePriorityBySourceRef(eq(ALARM_NO), any());
    }

    @Test
    @DisplayName("②b 告警 triggered 无在途/无监测挂接：零副作用（零 CAS 零任务面）")
    void escalateOnAlarmTriggeredNoInflightZeroSideEffects() {
        // 无在途输液行：直出零命中
        when(executionMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.escalateOnAlarmTriggered(PATIENT, ALARM_NO)).isZero();
        verify(executionMapper, never()).casEscalateAlarm(any(), any(), any());
        verifyNoInteractions(taskMapper);

        // 有在途行但挂接非 MONITORING（已收口）：同样零副作用
        when(executionMapper.selectList(any())).thenReturn(List.of(executingInfusion()));
        InfusionMonitorLink endedLinkRow = endedLink();
        when(monitorLinkMapper.selectList(any())).thenReturn(List.of(endedLinkRow));
        assertThat(service.escalateOnAlarmTriggered(PATIENT, ALARM_NO)).isZero();
        verify(executionMapper, never()).casEscalateAlarm(any(), any(), any());
        verifyNoInteractions(taskMapper);
    }

    @Test
    @DisplayName("②c 升级大屏推送（Task 11 接线）：INFUSION_ESCALATION 帧路由命中行病区，载荷=alarmNo/患者维/升级行数/任务上调数")
    void escalateMonitoredPushesInfusionEscalationBoardEvent() {
        when(executionMapper.selectList(any())).thenReturn(List.of(executingInfusion()));
        when(monitorLinkMapper.selectList(any())).thenReturn(List.of(monitoringLink()));
        when(executionMapper.casEscalateAlarm(eq(EXEC), eq(ALARM_NO), any())).thenReturn(1);
        when(taskMapper.casEscalatePriorityBySourceRef(eq(ALARM_NO), any())).thenReturn(1);

        assertThat(service.escalateOnAlarmTriggered(PATIENT, ALARM_NO)).isEqualTo(1);

        // 大屏强提醒帧：事务内发布 AFTER_COMMIT 出站；一病区一帧（命中行归属去重）
        org.mockito.ArgumentCaptor<NurseBoardPushEvent> pushCaptor =
                org.mockito.ArgumentCaptor.forClass(NurseBoardPushEvent.class);
        verify(events).publishEvent(pushCaptor.capture());
        NurseBoardPushEvent push = pushCaptor.getValue();
        assertThat(push.wardId()).isEqualTo(WARD);
        assertThat(push.type()).isEqualTo(NurseBoardPushFrame.TYPE_INFUSION_ESCALATION);
        NurseBoardPushFrame.InfusionEscalationPayload payload =
                (NurseBoardPushFrame.InfusionEscalationPayload) push.payload();
        assertThat(payload.alarmNo()).isEqualTo(ALARM_NO);
        assertThat(payload.executionNos()).containsExactly(EXEC);
        assertThat(payload.escalatedCount()).isEqualTo(1);
        assertThat(payload.taskEscalatedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("④告警 escalated 同款累计：latest_alarm_no 反查挂接行——CAS 再累计（并发零行幂等跳过）")
    void escalateOnAlarmEscalatedAccumulatesByAlarmAnchor() {
        OrderExecution anchored = executingInfusion();
        when(executionMapper.selectList(any())).thenReturn(List.of(anchored));
        when(executionMapper.casEscalateAlarm(eq(EXEC), eq(ALARM_NO), any()))
                .thenReturn(1)
                .thenReturn(0);

        // 首次：升级累计命中
        assertThat(service.escalateOnAlarmEscalated(ALARM_NO)).isEqualTo(1);
        verify(executionMapper).casEscalateAlarm(eq(EXEC), eq(ALARM_NO), any());
        verify(taskMapper).casEscalatePriorityBySourceRef(eq(ALARM_NO), any());

        // 并发零行（他方先终态）：幂等跳过——挂接锚不重复刷新（仅首次命中那一次）、计数不加；
        // 任务上调按事件维每次触发一次（priority<>'HIGH' 谓词承载幂等，重复写零行）
        assertThat(service.escalateOnAlarmEscalated(ALARM_NO)).isZero();
        verify(monitorLinkMapper, times(1)).casMarkAlarm(eq(EXEC), eq(ALARM_NO), any());
        verify(taskMapper, times(2)).casEscalatePriorityBySourceRef(eq(ALARM_NO), any());

        // 反查零命中（告警无挂接在途行）：零副作用
        when(executionMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.escalateOnAlarmEscalated(ALARM_NO)).isZero();
    }

    @Test
    @DisplayName("③告警 closed 复位：latest_alarm_no 双复位 NULL（escalation_count 保留追溯）")
    void resetAlarmClosedResetsAnchors() {
        when(executionMapper.casResetAlarmByAlarmNo(eq(ALARM_NO), any())).thenReturn(1);
        when(monitorLinkMapper.casResetAlarmByAlarmNo(eq(ALARM_NO), any())).thenReturn(1);

        int reset = service.resetAlarmClosed(ALARM_NO);

        assertThat(reset).isEqualTo(1);
        // 执行单行+挂接行双复位；升级计数 CAS 不触达（保留追溯）
        verify(executionMapper).casResetAlarmByAlarmNo(eq(ALARM_NO), any());
        verify(monitorLinkMapper).casResetAlarmByAlarmNo(eq(ALARM_NO), any());
        verify(executionMapper, never()).casEscalateAlarm(any(), any(), any());
    }

    @Test
    @DisplayName("升级路操作者缺位回退 system（MQ 桥接缺席防御——审计列默认同源）")
    void escalationFallsBackToSystemOperatorWithoutContext() {
        OperatorContextHolder.clear();
        when(executionMapper.selectList(any())).thenReturn(List.of(executingInfusion()));
        when(monitorLinkMapper.selectList(any())).thenReturn(List.of(monitoringLink()));
        when(executionMapper.casEscalateAlarm(eq(EXEC), eq(ALARM_NO), eq("system")))
                .thenReturn(1);
        when(taskMapper.casEscalatePriorityBySourceRef(eq(ALARM_NO), eq("system")))
                .thenReturn(0);

        assertThat(service.escalateOnAlarmTriggered(PATIENT, ALARM_NO)).isEqualTo(1);
        verify(executionMapper).casEscalateAlarm(eq(EXEC), eq(ALARM_NO), eq("system"));
        verify(taskMapper).casEscalatePriorityBySourceRef(eq(ALARM_NO), eq("system"));
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 在途输液执行单替身（EXECUTING+INFUSION）。 */
    private OrderExecution executingInfusion() {
        OrderExecution row = new OrderExecution();
        row.setId(1L);
        row.setExecutionNo(EXEC);
        row.setVisitId(VISIT);
        row.setPatientId(PATIENT);
        row.setWardId(WARD);
        row.setBedNo("03");
        row.setExecutionType(ExecutionType.INFUSION.getCode());
        row.setStatus(ExecutionStatus.EXECUTING.getCode());
        row.setEscalationCount(0);
        row.setLatestAlarmNo(ALARM_NO);
        return row;
    }

    /** 监测中挂接替身（MONITORING+袋签+设备+挂单锚）。 */
    private InfusionMonitorLink monitoringLink() {
        InfusionMonitorLink link = new InfusionMonitorLink();
        link.setExecutionNo(EXEC);
        link.setBagLabelCode(BAG_LABEL);
        link.setIotDeviceId(PUMP);
        link.setLatestAlarmNo(ALARM_NO);
        link.setLinkStatus("MONITORING");
        link.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30));
        return link;
    }

    /** 已收口挂接替身（ENDED——非监测中分支断言基准）。 */
    private InfusionMonitorLink endedLink() {
        InfusionMonitorLink link = new InfusionMonitorLink();
        link.setExecutionNo(EXEC);
        link.setBagLabelCode(BAG_LABEL);
        link.setLinkStatus("ENDED");
        link.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        link.setEndedAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        return link;
    }
}
