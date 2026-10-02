package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.AdverseEvent;
import java.time.OffsetDateTime;

/**
 * 不良事件出参（清单/操作回读共用面）。非惩罚红线（Spec 护理不良事件文化）：出参零惩罚
 * 字段——不含上报人身份（reporterId 禁入出参，组件名冻结面由 AdverseEventServiceImplTest
 * 反射断言锚定）；isAnonymous 仅承载通道标识、deadlineMet 仅承载时限合规（流程改进面），
 * 均非个人惩罚面。
 *
 * @param id              不良事件行 id
 * @param eventNo         不良事件业务号（AE+yyyyMMdd+5 位流水）
 * @param category        事件类别（AdverseEventCategory 八词表 code）
 * @param severityClass   严重度分级（I/II/III/IV）
 * @param severityGrade   严重度等级（A~E）
 * @param wardId          发生病区编码
 * @param visitId         住院就诊号（可空）
 * @param patientId       患者主索引（可空）
 * @param occurredAt      事件发生时点
 * @param eventSummary    事件经过
 * @param handlingNote    处置情况（处置进展随动作更新）
 * @param isAnonymous     匿名上报标识（通道标识，非惩罚面）
 * @param reportDeadline  上报时限基准（I/II 级=occurredAt+24h；III/IV 级 null）
 * @param deadlineMet     时限达成（超时留痕 false；未判定 null——流程改进面）
 * @param status          处置状态（REPORTED/HANDLING/CLOSED）
 * @param handlerId       处置责任人员工 ID（可空——未处置）
 * @param rcaNote         根因分析记录（可空——关闭时补录）
 * @param correctiveAction 整改措施（可空——关闭时补录）
 * @param createdAt       创建时刻
 * @param updatedAt       更新时刻
 */
public record AdverseEventVO(
        Long id,
        String eventNo,
        String category,
        String severityClass,
        String severityGrade,
        String wardId,
        String visitId,
        Long patientId,
        OffsetDateTime occurredAt,
        String eventSummary,
        String handlingNote,
        Boolean isAnonymous,
        OffsetDateTime reportDeadline,
        Boolean deadlineMet,
        String status,
        Long handlerId,
        String rcaNote,
        String correctiveAction,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例）。
     * 非惩罚红线：reporterId 不映射（实体字段到出参的单向剪除）。
     *
     * @param entity 不良事件行，非空
     * @return 不良事件出参，非空
     */
    public static AdverseEventVO from(AdverseEvent entity) {
        return new AdverseEventVO(
                entity.getId(),
                entity.getEventNo(),
                entity.getCategory(),
                entity.getSeverityClass(),
                entity.getSeverityGrade(),
                entity.getWardId(),
                entity.getVisitId(),
                entity.getPatientId(),
                entity.getOccurredAt(),
                entity.getEventSummary(),
                entity.getHandlingNote(),
                entity.getIsAnonymous(),
                entity.getReportDeadline(),
                entity.getDeadlineMet(),
                entity.getStatus(),
                entity.getHandlerId(),
                entity.getRcaNote(),
                entity.getCorrectiveAction(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
