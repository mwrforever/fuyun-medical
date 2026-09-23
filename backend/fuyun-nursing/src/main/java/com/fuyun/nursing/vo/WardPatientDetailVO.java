package com.fuyun.nursing.vo;

import com.fuyun.patient.api.AllergyItem;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 患者详情卡出参（GET /api/v1/nursing/ward-patients/{visitId}，冻结字段面）：
 * 基础视图属性 + 过敏实时嵌查（AllergyChecker）+ 当班责任护士 + 在途任务段。
 *
 * <p><b>不含体征摘要</b>——「最新体征」由前端另调 GET /api/v1/nursing/vital-signs 组装
 * （避免 WardMetaServiceImpl ↔ VitalSignServiceImpl 循环依赖：体征服务已单向依赖病区服务做在区校验）。
 * inFlightTasks 由 INursingTaskService#inFlightByVisit 实时填充（Task 7 补入；仅 PENDING/
 * IN_PROGRESS 行，读时惰性逾期判定后 overdueFlag 与库态一致）。
 *
 * @param wardId        病区编码
 * @param bedNo         床位号
 * @param patientId     患者主索引（MERGED 收敛主档口径）
 * @param visitId       住院就诊号
 * @param patientName   患者展示名
 * @param gender        性别 code（M01 字典）
 * @param age           年龄（岁）
 * @param nursingLevel  护理级别（NursingLevel code）
 * @param conditionTags 病情状态标记（逗号分隔展示镜像）
 * @param allergyFlag   过敏标识（订阅缓存镜像；明细以 allergies 实时嵌查为准）
 * @param riskFlags     风险标识（逗号分隔：FALL/PRESSURE）
 * @param admittedAt    入区时间
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
        String conditionTags,
        Boolean allergyFlag,
        String riskFlags,
        OffsetDateTime admittedAt,
        List<AllergyItem> allergies,
        List<NurseAssignmentVO> assignments,
        List<NursingTaskVO> inFlightTasks) {}
