package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.NursingAssessment;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 护理评估单出参（创建/患者清单共用回读面）。answers 为条目应答快照（服务端经 Jackson 把
 * JSONB 文本还原为结构化 Map，前端直接渲染）；triggeredTaskRef 仅高危行有值（联动防范任务号）；
 * adverseEventRef 为 P2 事件后回评预留（P1 恒空）。
 *
 * @param id              评估单行 id
 * @param assessNo        评估单号（AS+yyyyMMdd+5 位流水）
 * @param patientId       患者主索引
 * @param visitId         住院就诊号
 * @param wardId          病区编码
 * @param scaleType       量表类型（ScaleType code）
 * @param answers         条目应答快照（itemCode → 分值）
 * @param totalScore      量表总分
 * @param riskLevel       风险等级（RiskLevel code：HIGH/MEDIUM/LOW）
 * @param assessedAt      评估时点（临床实际评估时刻，业务时间口径）
 * @param assessedBy      评估人
 * @param nextAssessPlan  下次复评计划（assessedAt + 风险等级复评周期）
 * @param triggeredTaskRef 防范任务引用（nursing_task.task_no，仅高危行有值）
 * @param adverseEventRef 事件后回评引用（FU-M05-09 归 P2，P1 恒空）
 */
public record NursingAssessmentVO(
        Long id,
        String assessNo,
        Long patientId,
        String visitId,
        String wardId,
        String scaleType,
        Map<String, Integer> answers,
        Integer totalScore,
        String riskLevel,
        OffsetDateTime assessedAt,
        String assessedBy,
        OffsetDateTime nextAssessPlan,
        String triggeredTaskRef,
        String adverseEventRef) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例；
     * answers 由服务层解析 JSONB 文本后传入，本工厂不做结构解析）。
     *
     * @param entity  护理评估单行，非空
     * @param answers 条目应答快照结构化值（可空，解析失败由服务层先行定性）；来源：entity.answers 反序列化
     * @return 护理评估单出参，非空
     */
    public static NursingAssessmentVO from(NursingAssessment entity, Map<String, Integer> answers) {
        return new NursingAssessmentVO(
                entity.getId(),
                entity.getAssessNo(),
                entity.getPatientId(),
                entity.getVisitId(),
                entity.getWardId(),
                entity.getScaleType(),
                answers,
                entity.getTotalScore(),
                entity.getRiskLevel(),
                entity.getAssessedAt(),
                entity.getAssessedBy(),
                entity.getNextAssessPlan(),
                entity.getTriggeredTaskRef(),
                entity.getAdverseEventRef());
    }
}
