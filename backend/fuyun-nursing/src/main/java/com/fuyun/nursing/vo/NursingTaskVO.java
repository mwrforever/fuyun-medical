package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.NursingTask;
import java.time.OffsetDateTime;

/**
 * 护理任务出参（创建/完成/取消/清单/在途查询/巡视打卡共用回读面）。overdueFlag 与
 * escalationCount 为「动作式逾期」结果快照（读时惰性判定后与库态一致）；cancelReason 仅
 * 取消行有值；IN_PROGRESS 为 P1 声明态（无迁移入口，P2 任务工作台引入）。
 *
 * @param id              任务行 id
 * @param taskNo          任务业务号（TK+yyyyMMdd+5 位流水）
 * @param patientId       患者主索引
 * @param visitId         住院就诊号
 * @param wardId          病区编码
 * @param bedNo           床位号（冗余展示，可空）
 * @param taskType        任务类型（TaskType code）
 * @param source          任务来源（TaskSource code）
 * @param sourceRef       来源引用（执行单号/评估单号等，可空）
 * @param planTime        计划时间（逾期判定基准）
 * @param assignedNurse   责任护士（空=未指派）
 * @param priority        优先级（TaskPriority code）
 * @param overdueFlag     逾期标记（动作式，非状态）
 * @param escalationCount 升级次数（P1 惰性判定仅首次递增）
 * @param status          任务状态（TaskStatus code：PENDING/IN_PROGRESS/COMPLETED/CANCELLED）
 * @param completedAt     完成时间（完成/打卡行有值）
 * @param cancelReason    取消原因（取消行有值）
 */
public record NursingTaskVO(
        Long id,
        String taskNo,
        Long patientId,
        String visitId,
        String wardId,
        String bedNo,
        String taskType,
        String source,
        String sourceRef,
        OffsetDateTime planTime,
        String assignedNurse,
        String priority,
        Boolean overdueFlag,
        Integer escalationCount,
        String status,
        OffsetDateTime completedAt,
        String cancelReason) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例）。
     *
     * @param entity 护理任务行，非空
     * @return 护理任务出参，非空
     */
    public static NursingTaskVO from(NursingTask entity) {
        return new NursingTaskVO(
                entity.getId(),
                entity.getTaskNo(),
                entity.getPatientId(),
                entity.getVisitId(),
                entity.getWardId(),
                entity.getBedNo(),
                entity.getTaskType(),
                entity.getSource(),
                entity.getSourceRef(),
                entity.getPlanTime(),
                entity.getAssignedNurse(),
                entity.getPriority(),
                entity.getOverdueFlag(),
                entity.getEscalationCount(),
                entity.getStatus(),
                entity.getCompletedAt(),
                entity.getCancelReason());
    }
}
