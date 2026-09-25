package com.fuyun.inpatient.vo;

import java.time.OffsetDateTime;

/**
 * 病区欠费清单行（GET /visits/arrears?wardId= 出参——护士站欠费标识工作列表，五大降级清单②
 * 「欠费提醒=工作站列表可见」承载面；实体禁直出——出网边界唯一出口）。患者摘要脱敏红线
 * （GC22）：patientName 为 PatientNameQuery 脱敏展示名（保留姓氏、其余打星，姓名原文不出
 * patient 模块），禁全名/身份证等敏感明文；金额不出网（GC18——欠费明细归 M13 前端直调面）。
 *
 * @param visitId    住院就诊号（I 型 14 位，string 承载），非空
 * @param patientName 患者脱敏展示名（如 张*），可空（主档姓名缺失/单字掩码原样语义）
 * @param bedNo      床位号（病区内唯一；当前床位行缺失兜底 null），可空
 * @param flaggedAt  欠费标识时点，可空——以 inpatient_visit.updated_at 近似承载（V902 无专用
 *                   置位列且本任务零新迁移：标识 CAS 翻转时 fuyun_set_updated_at 触发器刷新
 *                   updated_at，置位与刷新同语句同瞬；同窗口内其他列并发更新会前移该值，仅作
 *                   护士站排序参考非审计口径）
 */
public record ArrearsAlarmVO(String visitId, String patientName, String bedNo, OffsetDateTime flaggedAt) {}
