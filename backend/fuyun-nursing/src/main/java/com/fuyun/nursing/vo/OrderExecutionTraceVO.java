package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.ExecutionCheckLog;
import com.fuyun.nursing.entity.OrderExecution;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 执行单闭环追溯出参（GET /api/v1/nursing/executions/{no}/trace）：单条执行单全环节
 * 人/时/码一屏可溯——五环节时点（签收/核对/开始/完成/拔针）+ 扫码核对流水清单（升序）+
 * 关联告警号。
 *
 * @param executionNo   执行单业务号
 * @param m04OrderNo    M04 医嘱号
 * @param m04PlanNo     M04 计划号（临时单为 null）
 * @param visitId       住院就诊号
 * @param patientId     患者主索引
 * @param status        执行单状态（ExecutionStatus 六态 code）
 * @param planTime      计划执行时间
 * @param signedAt      签收时点（可空）
 * @param checkedAt     核对通过时点（可空）
 * @param startedAt     开始执行时点（可空）
 * @param finishedAt    执行完成时点（可空）
 * @param needleOutAt   拔针时点（可空）
 * @param executorId    执行护士员工 ID（可空）
 * @param checkerId     核对护士员工 ID（可空）
 * @param overrideFlag  破码放行标识
 * @param cancelReason  撤销原因（撤销行有值）
 * @param confirmStatus 回签对账状态
 * @param latestAlarmNo 最新关联告警号（可空——IoT 输液告急挂接锚）
 * @param checkLogs     扫码核对流水（occurred_at 升序；含破码放行 OVERRIDE 行）
 */
public record OrderExecutionTraceVO(
        String executionNo,
        String m04OrderNo,
        String m04PlanNo,
        String visitId,
        Long patientId,
        String status,
        OffsetDateTime planTime,
        OffsetDateTime signedAt,
        OffsetDateTime checkedAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime needleOutAt,
        Long executorId,
        Long checkerId,
        Boolean overrideFlag,
        String cancelReason,
        String confirmStatus,
        String latestAlarmNo,
        List<CheckLogVO> checkLogs) {

    /**
     * 执行单核对流水条目（只增表读侧面：脱敏摘要原样承载，条码原文禁出参）。
     *
     * @param checkType  核对方式（CheckType code：WRISTBAND/BAG_LABEL/DEVICE/OVERRIDE）
     * @param checkResult 核对结论（PASS/FAIL）
     * @param failType   失败类型（FAIL 行有值：WRISTBAND_MISMATCH/BAG_MISMATCH/DEVICE_MISMATCH 等）
     * @param codeDigest 扫码/放行理由脱敏摘要（前 4 后 2 明文+总长度）
     * @param operatorId 核对操作护士员工 ID（OVERRIDE 行=主授权人）
     * @param occurredAt 核对发生时点
     */
    public record CheckLogVO(
            String checkType,
            String checkResult,
            String failType,
            String codeDigest,
            Long operatorId,
            OffsetDateTime occurredAt) {

        /**
         * 实体→出参静态工厂（手写映射，禁 MapStruct——A.1-8 先例）。
         *
         * @param entity 核对流水行，非空
         * @return 流水条目出参，非空
         */
        public static CheckLogVO from(ExecutionCheckLog entity) {
            return new CheckLogVO(
                    entity.getCheckType(),
                    entity.getCheckResult(),
                    entity.getFailType(),
                    entity.getCodeDigest(),
                    entity.getOperatorId(),
                    entity.getOccurredAt());
        }
    }

    /**
     * 聚合构造静态工厂（执行单行 + 核对流水清单 → 追溯出参）。
     *
     * @param row       执行单行，非空
     * @param checkLogs 核对流水（已按 occurred_at 升序），非空（可为空清单）
     * @return 追溯聚合出参，非空
     */
    public static OrderExecutionTraceVO of(OrderExecution row, List<CheckLogVO> checkLogs) {
        return new OrderExecutionTraceVO(
                row.getExecutionNo(),
                row.getM04OrderNo(),
                row.getM04PlanNo(),
                row.getVisitId(),
                row.getPatientId(),
                row.getStatus(),
                row.getPlanTime(),
                row.getSignedAt(),
                row.getCheckedAt(),
                row.getStartedAt(),
                row.getFinishedAt(),
                row.getNeedleOutAt(),
                row.getExecutorId(),
                row.getCheckerId(),
                row.getOverrideFlag(),
                row.getCancelReason(),
                row.getConfirmStatus(),
                row.getLatestAlarmNo(),
                checkLogs);
    }
}
