package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.context.RoleContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.api.ExecuteConfirmRequest;
import com.fuyun.inpatient.api.OrderExecutionConfirmPort;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.api.OrderExecutionCompletedPayload;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.dto.CancelExecutionRequest;
import com.fuyun.nursing.dto.CheckRequest;
import com.fuyun.nursing.dto.FinishRequest;
import com.fuyun.nursing.dto.OverrideCheckRequest;
import com.fuyun.nursing.dto.SignReceiveRequest;
import com.fuyun.nursing.dto.StartRequest;
import com.fuyun.nursing.entity.ExecutionCheckLog;
import com.fuyun.nursing.entity.InfusionMonitorLink;
import com.fuyun.nursing.entity.NursingWardConfig;
import com.fuyun.nursing.entity.OrderExecution;
import com.fuyun.nursing.enums.ExecutionStatus;
import com.fuyun.nursing.enums.ExecutionType;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.ExecutionCheckLogMapper;
import com.fuyun.nursing.mapper.InfusionMonitorLinkMapper;
import com.fuyun.nursing.mapper.NursingWardConfigMapper;
import com.fuyun.nursing.mapper.OrderExecutionMapper;
import com.fuyun.nursing.vo.OrderExecutionTraceVO;
import com.fuyun.nursing.vo.OrderExecutionVO;
import java.time.LocalDate;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 执行单操作域单测（Task 5 brief 冻结用例组①–⑨ + 补充覆盖锚）：check PASS/FAIL 两路、
 * start 时间窗/破码放行、finish 双路回签成功/补偿态、cancel 输注中断携量回签、occupancy
 * 过滤、trace 聚合。MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息；
 * CAS 断言直读注解 SQL（GC26 可执行锚）。JaCoCo nursing.service.impl 1.00 行覆盖红线：
 * 补充锚覆盖守卫违例、摆药签收衔接两型、回签跳过/同步分支等全部分支面。
 */
@ExtendWith(MockitoExtension.class)
class OrderExecutionOperateServiceImplTest {

    /** 执行单号（业务号断言基准） */
    private static final String EXEC = "EX2026100200001";

    /** M04 医嘱号 */
    private static final String ORDER_NO = "M20261002001";

    /** M04 计划号（长期计划拆分行对账锚） */
    private static final String PLAN_NO = "PL2026100300001";

    /** I 型 14 位合法 visit_id */
    private static final String VISIT = "I2026100200001";

    /** 病区编码 */
    private static final String WARD = "W01";

    /** 核对/执行护士员工 ID（操作者上下文数字位） */
    private static final long NURSE = 1001L;

    /** 请求承载执行护士员工 ID */
    private static final long EXECUTOR = 9L;

    @Mock
    private OrderExecutionMapper executionMapper;

    @Mock
    private ExecutionCheckLogMapper checkLogMapper;

    @Mock
    private InfusionMonitorLinkMapper monitorLinkMapper;

    @Mock
    private NursingWardConfigMapper wardConfigMapper;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private OrderExecutionConfirmPort confirmPort;

    @Captor
    private ArgumentCaptor<ExecutionCheckLog> checkLogCaptor;

    @Captor
    private ArgumentCaptor<Object> eventCaptor;

    @Captor
    private ArgumentCaptor<ExecuteConfirmRequest> confirmCaptor;

    private OrderExecutionOperateServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（主表+流水+挂接+病区配置）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderExecution.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ExecutionCheckLog.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), InfusionMonitorLink.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingWardConfig.class);
    }

    @BeforeEach
    void setUp() {
        service = new OrderExecutionOperateServiceImpl(
                executionMapper, checkLogMapper, monitorLinkMapper, wardConfigMapper, events, confirmPort);
        ReflectionTestUtils.setField(service, "baseMapper", executionMapper);
        // 链式 lambdaQuery（A.4.3-13）走 getEntityClass（经 mapper 代理元数据解析），mock 下须显式注入
        ReflectionTestUtils.setField(service, "entityClass", OrderExecution.class);
        // REST 链路操作者=登录护士（ThreadLocal 自然透传口径，GC15）
        OperatorContextHolder.set(String.valueOf(NURSE));
    }

    @AfterEach
    void clearContext() {
        // 防御性清理：防操作者/角色串号泄漏到其他用例的审计断言
        OperatorContextHolder.clear();
        RoleContextHolder.clear();
    }

    @Test
    @DisplayName("① check PASS：腕带维匹配 SIGNED→CHECKED CAS+流水落行（PASS/脱敏摘要/无 fail_type）")
    void checkPassMigratesToCheckedAndLogsPassRow() {
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.SIGNED, ExecutionType.GENERIC, null));
        when(executionMapper.casCheckPassed(eq(EXEC), any(), eq(NURSE), any())).thenReturn(1);

        OrderExecutionVO vo = service.check(EXEC, new CheckRequest(VISIT, "WRISTBAND"));

        assertThat(vo.status()).isEqualTo(ExecutionStatus.CHECKED.getCode());
        // 数据库写操作：核对通过 CAS（SIGNED 谓词内）+ PASS 流水落行
        verify(executionMapper).casCheckPassed(eq(EXEC), any(), eq(NURSE), any());
        verify(checkLogMapper).insert(checkLogCaptor.capture());
        ExecutionCheckLog logRow = checkLogCaptor.getValue();
        assertThat(logRow.getCheckType()).isEqualTo("WRISTBAND");
        assertThat(logRow.getCheckResult()).isEqualTo("PASS");
        assertThat(logRow.getFailType()).isNull();
        assertThat(logRow.getOperatorId()).isEqualTo(NURSE);
        // 脱敏红线：前 4 后 2 明文+总长度（14 位 visit_id 禁全文落库）
        assertThat(logRow.getCodeDigest()).isEqualTo("I202..01(len=14)");
    }

    @Test
    @DisplayName("② check FAIL：瓶签维挂接缺行 NS-1022+FAIL 流水落行（BAG_MISMATCH）不迁移状态")
    void checkFailLogsFailRowWithoutMigration() {
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.SIGNED, ExecutionType.GENERIC, null));
        // 挂接缺行（未建链）——瓶签维失配
        when(monitorLinkMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.check(EXEC, new CheckRequest("BAG-NO-EXIST", "BAG_LABEL")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.EXECUTION_CHECK_FAILED));

        // FAIL 流水仍落行（fail_type 判定）且状态 CAS 不触发（不迁移）
        verify(checkLogMapper).insert(checkLogCaptor.capture());
        assertThat(checkLogCaptor.getValue().getCheckResult()).isEqualTo("FAIL");
        assertThat(checkLogCaptor.getValue().getFailType()).isEqualTo("BAG_MISMATCH");
        verify(executionMapper, never()).casCheckPassed(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("②b check 执行单维：扫码原文与路径执行单号匹配 PASS（dispatch §3 第三维裁决）")
    void checkDeviceDimensionMatchesExecutionNo() {
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.CREATED, ExecutionType.GENERIC, null));
        when(executionMapper.casCheckPassed(eq(EXEC), any(), eq(NURSE), any())).thenReturn(1);

        OrderExecutionVO vo = service.check(EXEC, new CheckRequest(EXEC, "DEVICE"));

        // CREATED 直核合同承载（非药品类生成即可核对，SIGNED 可跳过）
        assertThat(vo.status()).isEqualTo(ExecutionStatus.CHECKED.getCode());
        verify(checkLogMapper).insert(checkLogCaptor.capture());
        assertThat(checkLogCaptor.getValue().getCheckType()).isEqualTo("DEVICE");
        assertThat(checkLogCaptor.getValue().getCheckResult()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("②c check OVERRIDE 维：非扫码维度恒失配——FAIL 流水 fail_type=OTHER 兜底")
    void checkOverrideDimensionFailsWithOtherFailType() {
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.SIGNED, ExecutionType.GENERIC, null));

        assertThatThrownBy(() -> service.check(EXEC, new CheckRequest(EXEC, "OVERRIDE")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.EXECUTION_CHECK_FAILED));

        verify(checkLogMapper).insert(checkLogCaptor.capture());
        assertThat(checkLogCaptor.getValue().getFailType()).isEqualTo("OTHER");
    }

    @Test
    @DisplayName("③ start 时间窗外：计划时点偏离超窗（缺省 30 分钟）未破码 NS-1027 拒绝")
    void startOutsideWindowWithoutOverrideRejected() {
        OrderExecution target = row(ExecutionStatus.CHECKED, ExecutionType.GENERIC, null);
        target.setPlanTime(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));
        when(executionMapper.selectOne(any())).thenReturn(target);
        // 病区配置行缺失——回退缺省 30 分钟（V1107 列默认同源）
        when(wardConfigMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.start(EXEC, new StartRequest(EXECUTOR, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.EXECUTION_TIME_WINDOW));
        verify(executionMapper, never()).casStart(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("④ start 破码放行：行 override_flag=true 跳过时间窗校验（配置窗 10 分钟承载非缺省分支）")
    void startWithOverrideFlagSkipsWindowCheck() {
        OrderExecution target = row(ExecutionStatus.CHECKED, ExecutionType.GENERIC, null);
        target.setPlanTime(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));
        target.setOverrideFlag(true);
        when(executionMapper.selectOne(any())).thenReturn(target);
        NursingWardConfig config = new NursingWardConfig();
        config.setExecuteTimeWindowMinutes(10);
        when(wardConfigMapper.selectOne(any())).thenReturn(config);
        when(executionMapper.casStart(eq(EXEC), any(), eq(EXECUTOR), any())).thenReturn(1);

        OrderExecutionVO vo = service.start(EXEC, new StartRequest(EXECUTOR, null, null));

        assertThat(vo.status()).isEqualTo(ExecutionStatus.EXECUTING.getCode());
        verify(executionMapper).casStart(eq(EXEC), any(), eq(EXECUTOR), any());
    }

    @Test
    @DisplayName("④b start 请求位破码：overrideTimeWindow=true 三源合流放行（窗外迁 EXECUTING）")
    void startWithRequestOverrideSkipsWindowCheck() {
        OrderExecution target = row(ExecutionStatus.CHECKED, ExecutionType.GENERIC, null);
        target.setPlanTime(OffsetDateTime.now(ZoneOffset.UTC).minusHours(3));
        when(executionMapper.selectOne(any())).thenReturn(target);
        when(wardConfigMapper.selectOne(any())).thenReturn(null);
        when(executionMapper.casStart(eq(EXEC), any(), eq(EXECUTOR), any())).thenReturn(1);

        OrderExecutionVO vo = service.start(EXEC, new StartRequest(EXECUTOR, "PUMP-01", true));

        assertThat(vo.status()).isEqualTo(ExecutionStatus.EXECUTING.getCode());
    }

    @Test
    @DisplayName("⑤ finish 成功路径：CAS 迁移+辅路径回执事件（id 64 载荷）+主路径回签 CONFIRMED 置位")
    void finishPublishesEventAndConfirmsViaPort() {
        OrderExecution target = row(ExecutionStatus.EXECUTING, ExecutionType.GENERIC, null);
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30);
        target.setSignedAt(startedAt);
        target.setCheckedAt(startedAt);
        target.setStartedAt(startedAt);
        when(executionMapper.selectOne(any())).thenReturn(target);
        when(executionMapper.casFinish(eq(EXEC), any(), any())).thenReturn(1);
        when(executionMapper.casMarkConfirmStatus(eq(EXEC), eq("PENDING"), eq("CONFIRMED"), any()))
                .thenReturn(1);

        OrderExecutionVO vo = service.finish(EXEC, new FinishRequest(EXECUTOR, "静脉通路通畅"));

        assertThat(vo.status()).isEqualTo(ExecutionStatus.COMPLETED.getCode());
        // 辅路径：事务内发布回执事件（AFTER_COMMIT 出 MQ——id 64 载荷逐字）
        verify(events).publishEvent(eventCaptor.capture());
        NursingDomainEvent event = (NursingDomainEvent) eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(NursingMessagingConstants.EVENT_ORDER_EXECUTION_COMPLETED);
        OrderExecutionCompletedPayload payload = (OrderExecutionCompletedPayload) event.payload();
        assertThat(payload.executionNo()).isEqualTo(EXEC);
        assertThat(payload.m04PlanNo()).isEqualTo(PLAN_NO);
        assertThat(payload.m04OrderNo()).isEqualTo(ORDER_NO);
        assertThat(payload.executorId()).isEqualTo(EXECUTOR);
        assertThat(payload.finishedAt()).isNotNull();
        // 主路径：事务提交后（无事务环境直调——单测形态）进程内回签
        verify(confirmPort).executeConfirm(eq(PLAN_NO), confirmCaptor.capture());
        assertThat(confirmCaptor.getValue().executorId()).isEqualTo(EXECUTOR);
        assertThat(confirmCaptor.getValue().executedAt()).isNotNull();
        assertThat(confirmCaptor.getValue().routeCheckResult()).isEqualTo("静脉通路通畅");
        verify(executionMapper).casMarkConfirmStatus(eq(EXEC), eq("PENDING"), eq("CONFIRMED"), any());
    }

    @Test
    @DisplayName("⑥ finish 回签失败：端口异常置 COMPENSATING 不抛出（床旁不阻塞——终态照常返回）")
    void finishWithPortFailureMarksCompensatingWithoutThrowing() {
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.EXECUTING, ExecutionType.GENERIC, null));
        when(executionMapper.casFinish(eq(EXEC), any(), any())).thenReturn(1);
        when(confirmPort.executeConfirm(eq(PLAN_NO), any())).thenThrow(new IllegalStateException("M04 不可用模拟"));

        // 不抛出（床旁终态与回签对账解耦）——单次调用承载断言
        OrderExecutionVO[] holder = new OrderExecutionVO[1];
        assertThatCode(() -> holder[0] = service.finish(EXEC, new FinishRequest(EXECUTOR, null)))
                .doesNotThrowAnyException();
        assertThat(holder[0].status()).isEqualTo(ExecutionStatus.COMPLETED.getCode());
        // 主路径未达：PENDING→COMPENSATING（补偿扫描承载，tick 接线归 Task 9）
        verify(executionMapper).casMarkConfirmStatus(eq(EXEC), eq("PENDING"), eq("COMPENSATING"), any());
        verify(executionMapper, never()).casMarkConfirmStatus(any(), eq("PENDING"), eq("CONFIRMED"), any());
    }

    @Test
    @DisplayName("⑤b finish 事务内编排：同步注册 afterCommit 后触发回签（提交前禁直调）")
    void finishRegistersConfirmAfterCommitUnderActiveSynchronization() {
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.EXECUTING, ExecutionType.GENERIC, null));
        when(executionMapper.casFinish(eq(EXEC), any(), any())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.finish(EXEC, new FinishRequest(EXECUTOR, null));
            // 提交前禁直调（回签与业务事务解耦——失败不回滚床旁终态）
            verify(confirmPort, never()).executeConfirm(any(), any());
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(confirmPort).executeConfirm(eq(PLAN_NO), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("⑤c finish 临时单跳过双路回签：无 m04 计划锚不发事件不回签（差异注记口径）")
    void finishSkipsDualConfirmForAdhocExecution() {
        OrderExecution target = row(ExecutionStatus.EXECUTING, ExecutionType.GENERIC, null);
        target.setM04PlanNo(null);
        when(executionMapper.selectOne(any())).thenReturn(target);
        when(executionMapper.casFinish(eq(EXEC), any(), any())).thenReturn(1);

        OrderExecutionVO vo = service.finish(EXEC, new FinishRequest(EXECUTOR, null));

        assertThat(vo.status()).isEqualTo(ExecutionStatus.COMPLETED.getCode());
        verifyNoInteractions(events);
        verifyNoInteractions(confirmPort);
    }

    @Test
    @DisplayName("⑦ cancel 输注中断：INFUSION+EXECUTING 携量部分执行回签（双路+实际入量留痕）")
    void cancelInfusionInterruptConfirmsPartiallyWithVolume() {
        OrderExecution target = row(ExecutionStatus.EXECUTING, ExecutionType.INFUSION, null);
        target.setExecutorId(EXECUTOR);
        when(executionMapper.selectOne(any())).thenReturn(target);
        // 护士长权限近似（RBAC 归 PR-4 W-37）：操作者角色 ∈ override_roles（配置非缺省分支）
        RoleContextHolder.set(List.of("HEAD_NURSE"));
        NursingWardConfig config = new NursingWardConfig();
        config.setOverrideRoles("HEAD_NURSE,CHARGE_NURSE");
        when(wardConfigMapper.selectOne(any())).thenReturn(config);
        when(executionMapper.casCancelInfusionInterrupt(eq(EXEC), any(), any(), any()))
                .thenReturn(1);

        OrderExecutionVO vo = service.cancel(EXEC, new CancelExecutionRequest("患者诉不适中断", 120));

        assertThat(vo.status()).isEqualTo(ExecutionStatus.CANCELLED.getCode());
        // 部分执行回签（双路）：事件+端口均触发，回签留痕携带实际入量文本
        verify(events).publishEvent(any(NursingDomainEvent.class));
        verify(confirmPort).executeConfirm(eq(PLAN_NO), confirmCaptor.capture());
        assertThat(confirmCaptor.getValue().routeCheckResult()).contains("实际入量=120ml");
        assertThat(confirmCaptor.getValue().executedAt()).isNotNull();
    }

    @Test
    @DisplayName("⑧ occupancy 过滤：两过滤键均缺失 NS-1019；患者键在途清单直出")
    void occupancyRequiresAtLeastOneKeyAndListsRows() {
        // 两过滤键均缺失：显式拒绝
        assertThatThrownBy(() -> service.occupancy(null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));

        when(executionMapper.selectList(any()))
                .thenReturn(List.of(row(ExecutionStatus.EXECUTING, ExecutionType.GENERIC, null)));
        List<OrderExecutionVO> occupancy = service.occupancy(7L, null);

        assertThat(occupancy).hasSize(1);
        assertThat(occupancy.get(0).executionNo()).isEqualTo(EXEC);
        assertThat(occupancy.get(0).patientId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("⑨ trace 聚合：五环节时点+核对流水清单（升序映射）+关联告警一屏")
    void traceAggregatesRowAndCheckLogs() {
        OrderExecution target = row(ExecutionStatus.COMPLETED, ExecutionType.INFUSION, "ALM-20261002-01");
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        target.setSignedAt(startedAt);
        target.setCheckedAt(startedAt);
        target.setStartedAt(startedAt);
        target.setFinishedAt(startedAt.plusMinutes(40));
        target.setExecutorId(EXECUTOR);
        target.setCheckerId(NURSE);
        when(executionMapper.selectOne(any())).thenReturn(target);
        when(checkLogMapper.selectList(any())).thenReturn(List.of(passLog(), failLog()));

        OrderExecutionTraceVO vo = service.trace(EXEC);

        assertThat(vo.executionNo()).isEqualTo(EXEC);
        assertThat(vo.latestAlarmNo()).isEqualTo("ALM-20261002-01");
        assertThat(vo.executorId()).isEqualTo(EXECUTOR);
        assertThat(vo.checkerId()).isEqualTo(NURSE);
        assertThat(vo.checkLogs()).hasSize(2);
        assertThat(vo.checkLogs().get(0).checkResult()).isEqualTo("PASS");
        assertThat(vo.checkLogs().get(1).failType()).isEqualTo("BAG_MISMATCH");
    }

    // ===================== 补充覆盖锚（守卫/衔接/分支面） =====================

    @Test
    @DisplayName("工作台守卫：wardId 缺失/班次与状态 code 词表外 NS-1019 显式拒绝")
    void workbenchRejectsInvalidParams() {
        assertThatThrownBy(() -> service.listWorkbench(" ", null, null, null, 0, 20))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        assertThatThrownBy(() -> service.listWorkbench(WARD, null, "MIDNIGHT", null, 0, 20))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        assertThatThrownBy(() -> service.listWorkbench(WARD, null, null, "FOO", 0, 20))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
    }

    @Test
    @DisplayName("工作台分页：班次窗口（EVENING 跨日收口）+状态过滤+VO 映射+分页口径直出")
    void workbenchPagesWithShiftWindowAndStatus() {
        OrderExecution target = row(ExecutionStatus.EXECUTING, ExecutionType.GENERIC, null);
        Page<OrderExecution> pageResult = new Page<>(0, 20);
        pageResult.setRecords(List.of(target));
        pageResult.setTotal(1);
        when(executionMapper.selectPage(any(), any())).thenReturn(pageResult);

        PageResult<OrderExecutionVO> vo = service.listWorkbench(WARD, null, "EVENING", "EXECUTING", 0, 20);

        assertThat(vo.content()).hasSize(1);
        assertThat(vo.content().get(0).executionNo()).isEqualTo(EXEC);
        assertThat(vo.total()).isEqualTo(1L);
    }

    @Test
    @DisplayName("工作台 DAY 班次窗口与 finish CAS 零行守卫（班次分支/完成态守卫覆盖锚）")
    void workbenchDayShiftWindowAndFinishStateGuard() {
        // DAY 班次窗口（08:00–16:00 同日收口——班次映射 DAY 分支覆盖）
        Page<OrderExecution> pageResult = new Page<>(0, 20);
        pageResult.setRecords(List.of());
        pageResult.setTotal(0);
        when(executionMapper.selectPage(any(), any())).thenReturn(pageResult);
        assertThat(service.listWorkbench(WARD, LocalDate.of(2026, 10, 2), "DAY", null, 0, 20)
                        .total())
                .isZero();

        // finish CAS 零行（非 EXECUTING 态）→ NS-1021
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.EXECUTING, ExecutionType.GENERIC, null));
        when(executionMapper.casFinish(eq(EXEC), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.finish(EXEC, new FinishRequest(EXECUTOR, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.EXECUTION_STATE_NOT_ALLOWED));
        verifyNoInteractions(confirmPort);
    }

    @Test
    @DisplayName("补签收：CREATED→SIGNED 落签收护士；CAS 零行 NS-1021；操作者非数字 NS-1019")
    void signReceiveCoversGuardAndSuccessFaces() {
        when(executionMapper.casSignReceive(eq(EXEC), any(), eq(NURSE), any())).thenReturn(1);
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.CREATED, ExecutionType.GENERIC, null));

        OrderExecutionVO vo = service.signReceive(EXEC, new SignReceiveRequest("夜班补签"));
        assertThat(vo.status()).isEqualTo(ExecutionStatus.SIGNED.getCode());

        // CAS 零行（已签收/终态）→ NS-1021
        when(executionMapper.casSignReceive(eq(EXEC), any(), eq(NURSE), any())).thenReturn(0);
        assertThatThrownBy(() -> service.signReceive(EXEC, new SignReceiveRequest(null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.EXECUTION_STATE_NOT_ALLOWED));

        // 操作者上下文缺失（非 REST 链路直调形态）→ NS-1019 fail-closed
        OperatorContextHolder.clear();
        assertThatThrownBy(() -> service.signReceive(EXEC, new SignReceiveRequest(null)))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
    }

    @Test
    @DisplayName("check 守卫：执行单缺行 NS-1020；PASS 后 CAS 零行 NS-1021")
    void checkCoversNotFoundAndStateGuard() {
        when(executionMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.check(EXEC, new CheckRequest(VISIT, "WRISTBAND")))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.EXECUTION_NOT_FOUND));

        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.SIGNED, ExecutionType.GENERIC, null));
        when(executionMapper.casCheckPassed(eq(EXEC), any(), eq(NURSE), any())).thenReturn(0);
        assertThatThrownBy(() -> service.check(EXEC, new CheckRequest(VISIT, "WRISTBAND")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.EXECUTION_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("check 词表守卫：codeType 词表外 NS-1019；瓶签维挂接匹配 PASS")
    void checkRejectsUnknownCodeTypeAndPassesBagLabel() {
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.SIGNED, ExecutionType.INFUSION, null));
        assertThatThrownBy(() -> service.check(EXEC, new CheckRequest("X", "QR_CODE")))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));

        // 瓶签维：挂接在册且袋签码匹配 → PASS
        InfusionMonitorLink link = new InfusionMonitorLink();
        link.setExecutionNo(EXEC);
        link.setBagLabelCode("BAG2026100201");
        when(monitorLinkMapper.selectOne(any())).thenReturn(link);
        when(executionMapper.casCheckPassed(eq(EXEC), any(), eq(NURSE), any())).thenReturn(1);
        OrderExecutionVO vo = service.check(EXEC, new CheckRequest("BAG2026100201", "BAG_LABEL"));
        assertThat(vo.status()).isEqualTo(ExecutionStatus.CHECKED.getCode());
    }

    @Test
    @DisplayName("start 守卫：窗内 CAS 零行 NS-1021（时间窗前置放行面）")
    void startCoversStateGuardWithinWindow() {
        OrderExecution target = row(ExecutionStatus.CHECKED, ExecutionType.GENERIC, null);
        target.setPlanTime(OffsetDateTime.now(ZoneOffset.UTC));
        when(executionMapper.selectOne(any())).thenReturn(target);
        when(executionMapper.casStart(eq(EXEC), any(), eq(EXECUTOR), any())).thenReturn(0);

        assertThatThrownBy(() -> service.start(EXEC, new StartRequest(EXECUTOR, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.EXECUTION_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("cancel 未执行撤销：CREATED 态原因留痕；CAS 零行 NS-1021")
    void cancelPendingCoversSuccessAndGuard() {
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.CREATED, ExecutionType.GENERIC, null));
        when(executionMapper.casCancelPending(eq(EXEC), eq("医嘱停止"), any())).thenReturn(1);

        OrderExecutionVO vo = service.cancel(EXEC, new CancelExecutionRequest("医嘱停止", null));
        assertThat(vo.status()).isEqualTo(ExecutionStatus.CANCELLED.getCode());
        assertThat(vo.cancelReason()).isEqualTo("医嘱停止");

        when(executionMapper.casCancelPending(eq(EXEC), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.cancel(EXEC, new CancelExecutionRequest("重复撤销", null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.EXECUTION_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("cancel 守卫：GENERIC 型 EXECUTING 拒撤销 NS-1021；输注中断权限不符 NS-1023；中断 CAS 零行 NS-1021")
    void cancelCoversInterruptGuards() {
        // GENERIC 型执行中：输注中断特殊面不适用 → NS-1021
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.EXECUTING, ExecutionType.GENERIC, null));
        assertThatThrownBy(() -> service.cancel(EXEC, new CancelExecutionRequest("误操作", null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.EXECUTION_STATE_NOT_ALLOWED));

        // INFUSION 型执行中但角色不在 override_roles（缺省 HEAD_NURSE）→ NS-1023 近似权限拒绝
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.EXECUTING, ExecutionType.INFUSION, null));
        RoleContextHolder.set(List.of("NURSE"));
        when(wardConfigMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.cancel(EXEC, new CancelExecutionRequest("患者要求", null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.OVERRIDE_CHECK_INVALID));

        // 权限通过但 CAS 零行（并发他方先迁）→ NS-1021
        RoleContextHolder.set(List.of("HEAD_NURSE"));
        when(executionMapper.casCancelInfusionInterrupt(eq(EXEC), any(), any(), any()))
                .thenReturn(0);
        assertThatThrownBy(() -> service.cancel(EXEC, new CancelExecutionRequest("中断", null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.EXECUTION_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("cancel 输注中断未报量：actualVolumeMl 空留痕文本承载（未报实际入量）")
    void cancelInfusionInterruptWithoutVolumeConfirmsWithNote() {
        OrderExecution target = row(ExecutionStatus.EXECUTING, ExecutionType.INFUSION, null);
        target.setExecutorId(EXECUTOR);
        when(executionMapper.selectOne(any())).thenReturn(target);
        RoleContextHolder.set(List.of("HEAD_NURSE"));
        when(wardConfigMapper.selectOne(any())).thenReturn(null);
        when(executionMapper.casCancelInfusionInterrupt(eq(EXEC), any(), any(), any()))
                .thenReturn(1);

        OrderExecutionVO vo = service.cancel(EXEC, new CancelExecutionRequest("管道堵塞", null));

        assertThat(vo.status()).isEqualTo(ExecutionStatus.CANCELLED.getCode());
        verify(confirmPort).executeConfirm(eq(PLAN_NO), confirmCaptor.capture());
        assertThat(confirmCaptor.getValue().routeCheckResult()).contains("未报实际入量");
    }

    @Test
    @DisplayName("破码放行：双授权同一人 NS-1023；角色不符 NS-1023；通过置位+OVERRIDE 流水（短理由全遮蔽）")
    void overrideCheckCoversGuardsAndSuccess() {
        when(executionMapper.selectOne(any())).thenReturn(row(ExecutionStatus.SIGNED, ExecutionType.GENERIC, null));

        // 双授权同一人拒绝
        assertThatThrownBy(() -> service.overrideCheck(new OverrideCheckRequest(EXEC, 5L, 5L, "同一人")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.OVERRIDE_CHECK_INVALID));

        // 角色不在 override_roles（缺省 HEAD_NURSE，操作者无角色上下文）拒绝
        when(wardConfigMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.overrideCheck(new OverrideCheckRequest(EXEC, 5L, 6L, "临床判定")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(NursingErrorCode.OVERRIDE_CHECK_INVALID));

        // 通过：置位 CAS+OVERRIDE 流水（主授权人 operator_id，理由摘要短码全遮蔽）
        RoleContextHolder.set(List.of("HEAD_NURSE"));
        when(executionMapper.casMarkOverride(eq(EXEC), any())).thenReturn(1);
        OrderExecutionVO vo = service.overrideCheck(new OverrideCheckRequest(EXEC, 5L, 6L, "同意"));
        assertThat(vo.overrideFlag()).isTrue();
        verify(checkLogMapper).insert(checkLogCaptor.capture());
        assertThat(checkLogCaptor.getValue().getCheckType()).isEqualTo("OVERRIDE");
        assertThat(checkLogCaptor.getValue().getCheckResult()).isEqualTo("PASS");
        assertThat(checkLogCaptor.getValue().getOperatorId()).isEqualTo(5L);
        // 长度 ≤6 全遮蔽（防短码全露——脱敏红线短码分支）
        assertThat(checkLogCaptor.getValue().getCodeDigest()).isEqualTo("****(len=2)");
    }

    @Test
    @DisplayName("摆药签收衔接·非 PIVAS：仅批量签收零升格（INPATIENT_DOSE 早退分支）")
    void onDispenseCompletedSignsBatchOnlyForNonPiva() {
        when(executionMapper.casSignReceiveBatchByOrder(eq(ORDER_NO), any(), any()))
                .thenReturn(2);

        service.onDispenseCompleted(ORDER_NO, "INPATIENT_DOSE", "DP2026100200001", "BAG2026100201");

        verify(executionMapper).casSignReceiveBatchByOrder(eq(ORDER_NO), any(), any());
        verify(executionMapper, never()).casUpgradeInfusionByOrder(any(), any());
        verifyNoInteractions(monitorLinkMapper);
    }

    @Test
    @DisplayName("摆药签收衔接·PIVAS：批量签收+升格 INFUSION+升格行全集幂等建链（挂接撞 uk 零副作用）")
    void onDispenseCompletedUpgradesPivaAndBuildsLinks() {
        when(executionMapper.casSignReceiveBatchByOrder(eq(ORDER_NO), any(), any()))
                .thenReturn(1);
        when(executionMapper.casUpgradeInfusionByOrder(eq(ORDER_NO), any())).thenReturn(1);
        OrderExecution infusionOne = row(ExecutionStatus.SIGNED, ExecutionType.INFUSION, null);
        OrderExecution infusionTwo = row(ExecutionStatus.SIGNED, ExecutionType.INFUSION, null);
        infusionTwo.setExecutionNo("EX2026100200002");
        when(executionMapper.selectList(any())).thenReturn(List.of(infusionOne, infusionTwo));
        // 首行建链成功、次行撞 uk_monitor_link_execution 被吞（ON CONFLICT DO NOTHING 0 行）
        when(monitorLinkMapper.insertIgnoreExecutionConflict(any()))
                .thenReturn(1)
                .thenReturn(0);

        service.onDispenseCompleted(ORDER_NO, "INPATIENT_PIVA", "DP2026100200001", "BAG2026100201");

        verify(executionMapper).casUpgradeInfusionByOrder(eq(ORDER_NO), any());
        verify(monitorLinkMapper, times(2)).insertIgnoreExecutionConflict(any(InfusionMonitorLink.class));
        // GC26 可执行锚：幂等建链必须为 ON CONFLICT 部分唯一索引 DO NOTHING 注解 SQL
        assertThat(insertIgnoreLinkSql()).contains("ON CONFLICT (execution_no) WHERE deleted = 0 DO NOTHING");
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 执行单行替身（五环节操作目标；m04PlanNo 默认长期计划拆分行形态）。 */
    private OrderExecution row(ExecutionStatus status, ExecutionType type, String latestAlarmNo) {
        OrderExecution row = new OrderExecution();
        row.setId(1L);
        row.setExecutionNo(EXEC);
        row.setM04OrderNo(ORDER_NO);
        row.setM04PlanNo(PLAN_NO);
        row.setVisitId(VISIT);
        row.setPatientId(7L);
        row.setWardId(WARD);
        row.setBedNo("03");
        row.setExecutionType(type.getCode());
        row.setExecItemCode(ORDER_NO);
        row.setExecItemName("drug");
        row.setPlanTime(OffsetDateTime.now(ZoneOffset.UTC));
        row.setStatus(status.getCode());
        row.setConfirmStatus("PENDING");
        row.setOverrideFlag(false);
        row.setLatestAlarmNo(latestAlarmNo);
        return row;
    }

    /** 核对流水行替身（PASS 腕带维）。 */
    private ExecutionCheckLog passLog() {
        ExecutionCheckLog logRow = new ExecutionCheckLog();
        logRow.setExecutionNo(EXEC);
        logRow.setCheckType("WRISTBAND");
        logRow.setCheckResult("PASS");
        logRow.setCodeDigest("I202..01(len=14)");
        logRow.setOperatorId(NURSE);
        logRow.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(50));
        return logRow;
    }

    /** 核对流水行替身（FAIL 瓶签维）。 */
    private ExecutionCheckLog failLog() {
        ExecutionCheckLog logRow = new ExecutionCheckLog();
        logRow.setExecutionNo(EXEC);
        logRow.setCheckType("BAG_LABEL");
        logRow.setCheckResult("FAIL");
        logRow.setFailType("BAG_MISMATCH");
        logRow.setCodeDigest("BAG2..99(len=12)");
        logRow.setOperatorId(NURSE);
        logRow.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(49));
        return logRow;
    }

    /** 读取幂等建链注解 SQL 原文（GC26 可执行锚——直读注解防形态漂移）。 */
    private static String insertIgnoreLinkSql() {
        try {
            return InfusionMonitorLinkMapper.class
                    .getMethod("insertIgnoreExecutionConflict", InfusionMonitorLink.class)
                    .getAnnotation(org.apache.ibatis.annotations.Insert.class)
                    .value()[0];
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("幂等建链方法缺失，注解锚失效", e);
        }
    }
}
