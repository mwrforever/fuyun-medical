package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.OrderExecution;
import java.time.OffsetDateTime;

/**
 * 医嘱执行单出参（工作台清单/五环节操作回读/占用清单共用面）。生成域快照占位字段
 * （execItemCode=医嘱号占位）随摆药回填演进；confirmStatus 为回签对账状态（PENDING/
 * COMPENSATING/CONFIRMED）。
 *
 * @param id            执行单行 id
 * @param executionNo   执行单业务号（EX+yyyyMMdd+5 位流水）
 * @param m04OrderNo    M04 医嘱号
 * @param m04PlanNo     M04 计划号（临时单为 null）
 * @param visitId       住院就诊号（I 型 14 位）
 * @param patientId     患者主索引
 * @param wardId        病区编码
 * @param bedNo         床位号（冗余展示，可空）
 * @param executionType 执行类型（GENERIC/INFUSION）
 * @param execItemCode  执行项目编码（生成期为医嘱号占位）
 * @param execItemName  执行项目名称（生成期为转抄类型快照）
 * @param dosageText    用法用量文本（可空）
 * @param planTime      计划执行时间（逾期判定与时间窗基准）
 * @param status        执行单状态（ExecutionStatus 六态 code）
 * @param signedAt      签收时点（可空）
 * @param checkedAt     核对通过时点（可空）
 * @param startedAt     开始执行时点（可空）
 * @param finishedAt    执行完成时点（可空；输注中断撤销同落本时点）
 * @param needleOutAt   拔针时点（输液类专属，可空）
 * @param executorId    执行护士员工 ID（可空——生成期未定）
 * @param checkerId     核对护士员工 ID（可空）
 * @param overrideFlag  破码放行标识
 * @param cancelReason  撤销原因（撤销行有值）
 * @param confirmStatus 回签对账状态（PENDING/COMPENSATING/CONFIRMED）
 * @param latestAlarmNo 最新关联告警号（可空——IoT 挂接锚）
 */
public record OrderExecutionVO(
        Long id,
        String executionNo,
        String m04OrderNo,
        String m04PlanNo,
        String visitId,
        Long patientId,
        String wardId,
        String bedNo,
        String executionType,
        String execItemCode,
        String execItemName,
        String dosageText,
        OffsetDateTime planTime,
        String status,
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
        String latestAlarmNo) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例）。
     *
     * @param entity 执行单行，非空
     * @return 执行单出参，非空
     */
    public static OrderExecutionVO from(OrderExecution entity) {
        return new OrderExecutionVO(
                entity.getId(),
                entity.getExecutionNo(),
                entity.getM04OrderNo(),
                entity.getM04PlanNo(),
                entity.getVisitId(),
                entity.getPatientId(),
                entity.getWardId(),
                entity.getBedNo(),
                entity.getExecutionType(),
                entity.getExecItemCode(),
                entity.getExecItemName(),
                entity.getDosageText(),
                entity.getPlanTime(),
                entity.getStatus(),
                entity.getSignedAt(),
                entity.getCheckedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getNeedleOutAt(),
                entity.getExecutorId(),
                entity.getCheckerId(),
                entity.getOverrideFlag(),
                entity.getCancelReason(),
                entity.getConfirmStatus(),
                entity.getLatestAlarmNo());
    }
}
