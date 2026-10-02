package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.inpatient.api.ExecuteConfirmRequest;
import com.fuyun.inpatient.api.OrderExecutionConfirmPort;
import com.fuyun.nursing.entity.OrderExecution;
import com.fuyun.nursing.enums.ExecutionStatus;
import com.fuyun.nursing.enums.ExecutionType;
import com.fuyun.nursing.mapper.OrderExecutionMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 执行回签补偿扫描组件单测（Task 5，GC10 新增 internal 类覆盖义务）：空扫描零负担、
 * 补偿成功置位 CONFIRMED（回签+置位同事务）、单行失败保持 COMPENSATING 不阻断批次、
 * 临时单防御跳过、GC15 SYSTEM 桥接（入口落位/finally 清理）。
 */
@ExtendWith(MockitoExtension.class)
class ExecutionConfirmCompensatorTest {

    /** 系统触发面操作者（审计列断言基准） */
    private static final String SYSTEM = "SYSTEM";

    /** 执行单号 */
    private static final String EXEC = "EX2026100200001";

    /** M04 计划号 */
    private static final String PLAN_NO = "PL2026100300001";

    @Mock
    private OrderExecutionMapper executionMapper;

    @Mock
    private OrderExecutionConfirmPort confirmPort;

    private TransactionTemplate transactionTemplate;

    private ExecutionConfirmCompensator compensator;

    @AfterEach
    void clearOperator() {
        // 防御性清理：SYSTEM 桥接残留防线程复用串号
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("空扫描：COMPENSATING 零命中零负担返回 0（空 tick 幂等忽略）")
    void emptyScanReturnsZeroWithoutPortInteraction() {
        compensator = new ExecutionConfirmCompensator(executionMapper, confirmPort, txTemplate());
        when(executionMapper.selectList(any())).thenReturn(List.of());

        assertThat(compensator.compensate()).isZero();

        verifyNoInteractions(confirmPort);
    }

    @Test
    @DisplayName("补偿成功：回签端口调用+COMPENSATING→CONFIRMED 置位（SYSTEM 操作者审计）")
    void successfulCompensationConfirmsStatus() {
        compensator = new ExecutionConfirmCompensator(executionMapper, confirmPort, txTemplate());
        when(executionMapper.selectList(any())).thenReturn(List.of(compensatingRow()));
        when(executionMapper.casMarkConfirmStatus(eq(EXEC), eq("COMPENSATING"), eq("CONFIRMED"), eq(SYSTEM)))
                .thenReturn(1);

        assertThat(compensator.compensate()).isEqualTo(1);

        // 回签请求自执行单行回读：executorId/executedAt 承载、routeCheckResult null 透传（不可复原降级）
        verify(confirmPort).executeConfirm(eq(PLAN_NO), any(ExecuteConfirmRequest.class));
        verify(executionMapper).casMarkConfirmStatus(eq(EXEC), eq("COMPENSATING"), eq("CONFIRMED"), eq(SYSTEM));
    }

    @Test
    @DisplayName("补偿失败：端口异常保持 COMPENSATING 不抛出（单行失败不阻断批次）")
    void failedCompensationKeepsCompensatingWithoutThrowing() {
        compensator = new ExecutionConfirmCompensator(executionMapper, confirmPort, txTemplate());
        when(executionMapper.selectList(any())).thenReturn(List.of(compensatingRow()));
        when(confirmPort.executeConfirm(eq(PLAN_NO), any(ExecuteConfirmRequest.class)))
                .thenThrow(new IllegalStateException("M04 不可用模拟"));

        assertThat(compensator.compensate()).isZero();

        // 失败保持 COMPENSATING（置位语句不触达——事务内首步即失败回滚）
        verify(executionMapper, never()).casMarkConfirmStatus(any(), any(), any(), any());
    }

    @Test
    @DisplayName("临时单防御：m04PlanNo NULL 的脏数据行跳过重试（warn 留痕零端口交互）")
    void adhocRowWithoutPlanAnchorIsSkipped() {
        compensator = new ExecutionConfirmCompensator(executionMapper, confirmPort, txTemplate());
        OrderExecution adhoc = compensatingRow();
        adhoc.setM04PlanNo(null);
        when(executionMapper.selectList(any())).thenReturn(List.of(adhoc));

        assertThat(compensator.compensate()).isZero();

        verifyNoInteractions(confirmPort);
    }

    @Test
    @DisplayName("GC15 SYSTEM 桥接：扫描入口落位 SYSTEM、finally 清理防串号")
    void compensationBridgesSystemOperatorAndClears() {
        compensator = new ExecutionConfirmCompensator(executionMapper, confirmPort, txTemplate());
        // 扫描语句触达时回读操作者上下文——断言 SYSTEM 在位（消费链路桥接口径）
        java.util.concurrent.atomic.AtomicReference<String> operatorInScan =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(executionMapper.selectList(any())).thenAnswer(invocation -> {
            operatorInScan.set(OperatorContextHolder.get());
            return List.of();
        });

        assertThat(compensator.compensate()).isZero();

        assertThat(operatorInScan.get()).isEqualTo(SYSTEM);
        // finally 清理：扫描结束操作者上下文不残留
        assertThat(OperatorContextHolder.get()).isNull();
    }

    /**
     * 补偿候选行替身（终态+COMPENSATING+长期计划锚）。
     *
     * @return 执行单行，非空
     */
    private OrderExecution compensatingRow() {
        OrderExecution row = new OrderExecution();
        row.setExecutionNo(EXEC);
        row.setM04OrderNo("M20261002001");
        row.setM04PlanNo(PLAN_NO);
        row.setStatus(ExecutionStatus.COMPLETED.getCode());
        row.setExecutionType(ExecutionType.GENERIC.getCode());
        row.setConfirmStatus("COMPENSATING");
        row.setExecutorId(9L);
        row.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return row;
    }

    /**
     * 无资源事务模板（iot 段先例形态：单行「回签+置位」事务边界承载，无真实资源提交）。
     *
     * @return 编程式事务模板，非空
     */
    private TransactionTemplate txTemplate() {
        return new TransactionTemplate(
                new org.springframework.transaction.support.AbstractPlatformTransactionManager() {

                    @Override
                    protected Object doGetTransaction() {
                        return new Object();
                    }

                    @Override
                    protected void doBegin(
                            Object transaction, org.springframework.transaction.TransactionDefinition definition) {
                        // 无资源事务令牌：同步激活由父类统一完成
                    }

                    @Override
                    protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                        // 无资源 commit
                    }

                    @Override
                    protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
                        // 无资源 rollback：失败路径由调用方 catch 承载
                    }
                });
    }
}
