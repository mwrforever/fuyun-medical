package com.fuyun.nursing.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.inpatient.api.ExecuteConfirmRequest;
import com.fuyun.inpatient.api.OrderExecutionConfirmPort;
import com.fuyun.nursing.entity.OrderExecution;
import com.fuyun.nursing.mapper.OrderExecutionMapper;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 执行回签补偿扫描组件（M05，P2 PR-3 Task 5）：扫描 confirm_status=COMPENSATING 的终态
 * 执行单，逐条重试 M04 回签端口至 CONFIRMED（GC17 双路回签主路径的兜底面）。
 *
 * <p>触发形态：tick 驱动（delay.task-overdue 到期回投路由键消费侧）——tick 队列接线归
 * Task 9（本任务只落组件不接队列消费）；扫描有界（单轮 LIMIT 200、execution_no 升序——
 * 扫描排序确定性纪律），失败行保持 COMPENSATING 待下一轮重试（重试收敛由 tick 周期承载）。
 *
 * <p>GC15 operator 桥：tick 链路无登录上下文——入口显式 OperatorContextHolder.set
 * ("SYSTEM") + try/finally clear（inpatient 侧 parseOperatorAsEmployeeId SYSTEM 白名单
 * 放行，审计列落 SYSTEM 文本）。
 *
 * <p>单行回签经 TransactionTemplate 独立事务承载（成功置位 CONFIRMED 与回签调用同事务
 * 成败与共；失败仅留痕不回滚他行——单行失败不阻断批次）。回签入参自执行单行回读：
 * executorId=行 executor_id、executedAt=行 finished_at（部分执行中断同落 finished_at）、
 * routeCheckResult 补偿面不可复原（V1106 无承载列）以 null 承载——降级注记见任务报告。
 *
 * <p>归 internal/：禁外引；Bean 注册点 NursingMessagingConfig @Import。
 */
@Slf4j
public class ExecutionConfirmCompensator {

    /** 系统触发面操作者（GC15 桥接口径——与 inpatient 侧 SYSTEM 白名单字面量同源） */
    private static final String SYSTEM_OPERATOR_TEXT = "SYSTEM";

    /** 回签对账状态：补偿中（主路径未达，经本组件重试） */
    private static final String CONFIRM_COMPENSATING = "COMPENSATING";

    /** 回签对账状态：已对账（补偿重试成功终态） */
    private static final String CONFIRM_CONFIRMED = "CONFIRMED";

    /** 单轮扫描行数上界（tick 周期承载重试收敛——有界扫描纪律） */
    private static final int SCAN_LIMIT = 200;

    private final OrderExecutionMapper executionMapper;

    private final OrderExecutionConfirmPort confirmPort;

    private final TransactionTemplate transactionTemplate;

    /**
     * 全参构造器（装配归 NursingMessagingConfig @Import）。
     *
     * @param executionMapper    执行单 mapper，非空；补偿候选扫描与对账状态 CAS 回写面
     * @param confirmPort        M04 回签端口，非空；补偿重试主路径
     * @param transactionTemplate 编程式事务模板，非空；单行「回签+置位」独立事务承载
     */
    public ExecutionConfirmCompensator(
            OrderExecutionMapper executionMapper,
            OrderExecutionConfirmPort confirmPort,
            TransactionTemplate transactionTemplate) {
        this.executionMapper = executionMapper;
        this.confirmPort = confirmPort;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 补偿扫描入口（tick 消费者调用面，Task 9 接线）：COMPENSATING 行有界扫描逐条重试回签。
     * 单行失败不阻断批次（warn/error 留痕，保持 COMPENSATING 待下轮）；全批异常上抛由
     * tick 消费侧幂等语义承载。
     *
     * @return 本轮重试成功置位 CONFIRMED 的行数（观测口径，非失败判定）
     */
    public int compensate() {
        // GC15 operator 桥：tick 链路系统操作者落位（finally 清理防线程复用残留）
        OperatorContextHolder.set(SYSTEM_OPERATOR_TEXT);
        try {
            return doCompensate();
        } finally {
            OperatorContextHolder.clear();
        }
    }

    /**
     * 补偿扫描业务体（包级直驱可测）：有界扫描 → 逐行独立事务重试。
     *
     * @return 本轮成功置位行数
     */
    private int doCompensate() {
        // 数据库读操作：补偿候选有界扫描（confirm_status=COMPENSATING，execution_no 升序——
        // 排序确定性纪律；逻辑删由 @TableLogic 自动过滤）
        List<OrderExecution> candidates = executionMapper.selectList(new LambdaQueryWrapper<OrderExecution>()
                .eq(OrderExecution::getConfirmStatus, CONFIRM_COMPENSATING)
                .orderByAsc(OrderExecution::getExecutionNo)
                .last("LIMIT " + SCAN_LIMIT));
        if (candidates.isEmpty()) {
            log.debug("执行回签补偿扫描零命中（空 tick 幂等忽略）");
            return 0;
        }
        int confirmed = 0;
        for (OrderExecution row : candidates) {
            if (row.getM04PlanNo() == null) {
                // 防御面：临时单无计划锚不应进入补偿态（主路径前置跳过）——留痕跳过不重试
                log.warn(
                        "执行回签补偿跳过（临时单无计划对账锚，疑脏数据）：executionNo={}，confirmStatus={}",
                        row.getExecutionNo(),
                        row.getConfirmStatus());
                continue;
            }
            if (compensateRow(row)) {
                confirmed++;
            }
        }
        log.info("执行回签补偿扫描完成：候选={}，成功置位={}", candidates.size(), confirmed);
        return confirmed;
    }

    /**
     * 单行补偿重试（独立事务）：回签端口调用 + CONFIRMED 置位同事务成败与共。
     *
     * @param row 补偿候选行（m04PlanNo 非空已守卫），非空
     * @return true=本轮置位 CONFIRMED；false=重试仍失败（保持 COMPENSATING 待下轮）
     */
    private boolean compensateRow(OrderExecution row) {
        String executionNo = row.getExecutionNo();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 回签请求自执行单行回读：executorId/finishedAt 承载，routeCheckResult
                // 补偿面不可复原（无承载列）以 null 透传——给药途径核对留痕以主路径首达为准
                confirmPort.executeConfirm(
                        row.getM04PlanNo(), new ExecuteConfirmRequest(row.getExecutorId(), row.getFinishedAt(), null));
                // 数据库写操作：对账状态 COMPENSATING→CONFIRMED（0 行=并发他方已迁移，幂等留痕）
                int marked = executionMapper.casMarkConfirmStatus(
                        executionNo, CONFIRM_COMPENSATING, CONFIRM_CONFIRMED, SYSTEM_OPERATOR_TEXT);
                log.info(
                        "执行回签补偿成功：executionNo={}，planNo={}，confirmStatus 置位={}",
                        executionNo,
                        row.getM04PlanNo(),
                        marked);
            });
            return true;
        } catch (Exception e) {
            // 单行失败不阻断批次：保持 COMPENSATING 待下一轮 tick 重试（error 留痕含原因）
            log.error(
                    "执行回签补偿重试失败（保持 COMPENSATING 待下轮）：executionNo={}，planNo={}，原因={}",
                    executionNo,
                    row.getM04PlanNo(),
                    e.getMessage(),
                    e);
            return false;
        }
    }
}
