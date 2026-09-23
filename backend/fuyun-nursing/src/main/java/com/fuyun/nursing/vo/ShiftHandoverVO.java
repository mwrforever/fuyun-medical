package com.fuyun.nursing.vo;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 交接班出参（生成/完成/清单共用回读面）：患者摘要与待续事项为 JSONB 快照的服务端结构化面
 * （组件名与 V807 列注释 JSON 键逐字对齐）；pendingInfusions/unclosedAlarms 为 P1 恒空数组
 * 占位（M14/M16 接入后 P2 替换元素结构，Spec 注记登记）。
 *
 * @param id                  交接班行 id
 * @param handoverNo          交接班单业务号（HO+yyyyMMdd+5 位流水）
 * @param wardId              病区编码
 * @param shiftCode           班次 code（取病区班次定义）
 * @param handoverDate        交接班日期（列表按日检索键）
 * @param outgoingNurseId     交班护士（生成时当前操作者）
 * @param incomingNurseId     接班护士（完成签署时写入，未完成为 null）
 * @param patientSummary      患者摘要快照（在区总数/护理级别分布/病情标记计数）
 * @param sbarSituation       S 现状（自动汇总初稿 + 人工补充）
 * @param sbarBackground      B 背景（自动汇总初稿 + 人工补充）
 * @param sbarAssessment      A 评估（自动汇总初稿 + 人工补充）
 * @param sbarRecommendation  R 建议（自动汇总初稿 + 人工补充）
 * @param pendingItems        待续事项：在途任务清单（在途任务非空患者入选）
 * @param pendingInfusions    待续事项：在途输注（P1 恒空数组，P2 随 M14 接入）
 * @param unclosedAlarms      待续事项：未闭环告警（P1 恒空数组，P2 随 M16 接入）
 * @param outgoingSignedAt    交班签名时间（生成时刻盖章）
 * @param incomingSignedAt    接班签名时间（完成时刻盖章，未完成为 null）
 * @param status              交接班状态（HandoverStatus code：DRAFT/SIGNING/COMPLETED）
 */
public record ShiftHandoverVO(
        Long id,
        String handoverNo,
        String wardId,
        String shiftCode,
        LocalDate handoverDate,
        String outgoingNurseId,
        String incomingNurseId,
        PatientSummary patientSummary,
        String sbarSituation,
        String sbarBackground,
        String sbarAssessment,
        String sbarRecommendation,
        List<PendingItem> pendingItems,
        List<Object> pendingInfusions,
        List<Object> unclosedAlarms,
        OffsetDateTime outgoingSignedAt,
        OffsetDateTime incomingSignedAt,
        String status) {

    /**
     * 患者摘要快照（patient_summary JSONB 结构，组件名与 V807 列注释 JSON 键逐字对齐）。
     * todayDischargeCount/transferOutCount P1 恒 0——V801 病情标记词表（CRITICAL/SEVERE/NEW/
     * SURGERY/DELIVERY）无对应 code，M04 事件链 P2 接入后由视图标签驱动（DDL 契约位预置）。
     *
     * @param total               在区患者总数
     * @param specialCount        特级护理人数（NursingLevel SPECIAL）
     * @param criticalCount       病重护理人数（NursingLevel CRITICAL）
     * @param newAdmissionCount   新入人数（病情标记 NEW）
     * @param surgeryCount        手术人数（病情标记 SURGERY）
     * @param todayDischargeCount 今日出院人数（P1 恒 0，词表缺位注记）
     * @param transferOutCount    转出人数（P1 恒 0，词表缺位注记）
     */
    public record PatientSummary(
            int total,
            int specialCount,
            int criticalCount,
            int newAdmissionCount,
            int surgeryCount,
            int todayDischargeCount,
            int transferOutCount) {}

    /**
     * 待续事项在途任务条目（pending_items JSONB 数组元素结构，组件名与 V807 列注释逐字对齐）。
     *
     * @param taskNo     护理任务业务号（TK+yyyyMMdd+5 位流水）
     * @param taskType   任务类型（TaskType code）
     * @param planTime   计划时间（逾期判定基准）
     * @param overdueFlag 逾期标记（动作式，读时惰性判定后与库态一致）
     * @param visitId    所属住院就诊号（接班护士逐患者跟进定位锚）
     */
    public record PendingItem(
            String taskNo, String taskType, OffsetDateTime planTime, Boolean overdueFlag, String visitId) {}
}
