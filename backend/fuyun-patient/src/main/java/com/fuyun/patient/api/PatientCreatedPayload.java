package com.fuyun.patient.api;

/**
 * patient.patient.created 载荷（V105 id=9 冻结契约）：新档建立广播。敏感红线：不含姓名/证件号/手机号。
 *
 * @param patientId       新档患者 id；来源：本模块发号（雪花）
 * @param sex             性别字典 code；来源：建档录入
 * @param birthDate       出生日期（ISO-8601 文本，可空）；来源：建档录入
 * @param realNameFlag    实名标记（false=授权建档/核验降级未实名）；来源：建档流程
 * @param registerChannel 建档渠道 WINDOW/SELF_SERVICE/ONLINE/INPATIENT_REGISTER/EMERGENCY；来源：建档请求
 * @param archiveSource   档案来源 STANDARD/TEMP_ANONYMOUS/TEMP_NEWBORN；来源：建档请求
 */
public record PatientCreatedPayload(
        long patientId,
        String sex,
        String birthDate,
        boolean realNameFlag,
        String registerChannel,
        String archiveSource) {}
