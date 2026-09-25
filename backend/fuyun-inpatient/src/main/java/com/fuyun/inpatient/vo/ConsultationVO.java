package com.fuyun.inpatient.vo;

import com.fuyun.inpatient.entity.Consultation;
import java.time.OffsetDateTime;

/**
 * 会诊单出参（申请/接单/意见/取消/列表五面共用回显）：overdue_flag 为读时惰性逾期判定的
 * 可见锚（降级清单②——通知中心缺位期超时升级以工作站列表标记可见，动作事件为旁路广播）；
 * visitId 出参转写 I 型 14 位号（表内存就诊主键，V907 同款口径）。
 *
 * @param consultNo        会诊单号（CS+yyyyMMdd+5 位流水），非空
 * @param visitId          住院就诊号（I 型 14 位；表内存主键出参转号），非空
 * @param patientId        患者主索引，非空
 * @param orderRef         关联会诊医嘱号（钩子建单关联；独立申请为 null），可空
 * @param fromDeptId       申请科室编码（M01 组织 code），可空（就诊未入科的草稿防御）
 * @param toDeptId         受邀科室编码（M01 组织 code；钩子草稿未派单为 null），可空
 * @param level            会诊级别（DEPT/HOSPITAL/MDT 预留），非空
 * @param urgency          紧急程度（URGENT/NORMAL），非空
 * @param reason           申请原因，可空
 * @param requesterId      申请医生（员工 ID string），非空
 * @param requestedAt      申请时点，非空
 * @param responseDeadline 响应截止时点（URGENT +30min / NORMAL +24h），非空
 * @param responseTime     接单时点（未接单为 null），可空
 * @param consultTime      会诊完成时点（未完成为 null），可空
 * @param opinion          会诊意见（归档供 M09 引用；未完成为 null），可空
 * @param overdueFlag      逾期升级标记（true=已广播 overdue 动作事件；接单清零），非空
 * @param status           状态（ConsultationStatus 四值），非空
 */
public record ConsultationVO(
        String consultNo,
        String visitId,
        long patientId,
        String orderRef,
        String fromDeptId,
        String toDeptId,
        String level,
        String urgency,
        String reason,
        String requesterId,
        OffsetDateTime requestedAt,
        OffsetDateTime responseDeadline,
        OffsetDateTime responseTime,
        OffsetDateTime consultTime,
        String opinion,
        boolean overdueFlag,
        String status) {

    /**
     * 实体行 → 出参映射（visitId 号转写：表内存主键 → I 型 14 位号）。
     *
     * @param row     会诊单实体行，非空
     * @param visitNo 住院就诊号（I 型 14 位，调用方已取数），非空
     * @return 会诊单出参，非空
     */
    public static ConsultationVO from(Consultation row, String visitNo) {
        return new ConsultationVO(
                row.getConsultNo(),
                visitNo,
                row.getPatientId(),
                row.getOrderRef(),
                row.getFromDeptId(),
                row.getToDeptId(),
                row.getLevel(),
                row.getUrgency(),
                row.getReason(),
                row.getRequesterId(),
                row.getRequestedAt(),
                row.getResponseDeadline(),
                row.getResponseTime(),
                row.getConsultTime(),
                row.getOpinion(),
                Boolean.TRUE.equals(row.getOverdueFlag()),
                row.getStatus());
    }
}
