package com.fuyun.nursing.vo;

import com.fuyun.patient.api.AllergyItem;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 患者详情卡出参（GET /api/v1/nursing/ward-patients/{visitId}，冻结字段面）：基础投影属性 +
 * 患者展示名（patient api 嵌查）+ 过敏实时嵌查（AllergyChecker）+ 当班责任护士 + 在途任务段。
 *
 * <p><b>W-34 退役（2026-10）</b>：conditionTags 病情标记组件退役——四事件载荷（admitted/
 * transferred/discharged/bed.changed）不可推导（GC39 判据），登记面（P1 过渡通道 conditionTags
 * 入参）已随端点退役，条件标签语义经评估域 riskFlags 承载；patientName 自投影行读改为 patient
 * api 嵌查（PatientNameQuery 脱敏展示名——事件载荷脱敏红线不携姓名）。
 *
 * <p><b>不含体征摘要</b>——「最新体征」由前端另调 GET /api/v1/nursing/vital-signs 组装
 * （避免 WardMetaServiceImpl ↔ VitalSignServiceImpl 循环依赖：体征服务已单向依赖病区服务做在区校验）。
 * inFlightTasks 由 INursingTaskService#inFlightByVisit 实时填充（仅 PENDING/
 * IN_PROGRESS 行，读时惰性逾期判定后 overdueFlag 与库态一致）。
 *
 * @param wardId        病区编码
 * @param bedNo         床位号（床号文本语义——bed.changed 补齐）
 * @param patientId     患者主索引（MERGED 收敛主档口径）
 * @param visitId       住院就诊号
 * @param patientName   患者脱敏展示名（patient api 嵌查，如 张*；患者行缺失时 null）
 * @param gender        性别 code（M01 字典；事件载荷不携，W-34 后投影行不落值）
 * @param age           年龄（岁；事件载荷不携，W-34 后投影行不落值）
 * @param nursingLevel  护理级别（NursingLevel code）
 * @param allergyFlag   过敏标识（订阅缓存镜像；明细以 allergies 实时嵌查为准）
 * @param riskFlags     风险标识（逗号分隔：FALL/PRESSURE，评估域回写）
 * @param admittedAt    入区时间（admitted 事件载荷权威承载）
 * @param allergies     当前有效过敏项（AllergyChecker 实时嵌查）
 * @param assignments   当班责任护士分配
 * @param inFlightTasks 在途任务（仅 PENDING/IN_PROGRESS，计划时间升序；无行返回空清单）
 */
public record WardPatientDetailVO(
        String wardId,
        String bedNo,
        Long patientId,
        String visitId,
        String patientName,
        String gender,
        Integer age,
        String nursingLevel,
        Boolean allergyFlag,
        String riskFlags,
        OffsetDateTime admittedAt,
        List<AllergyItem> allergies,
        List<NurseAssignmentVO> assignments,
        List<NursingTaskVO> inFlightTasks) {}
