package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 患者建档请求（POST /api/v1/patient/patients，FU-M02-01）。
 *
 * <p>必填口径对齐实名制（WS/T 840 身份核对要素）：姓名/性别/建档渠道/知情同意凭证引用必填；
 * 证件要素可空（无证件走授权建档，realNameFlag=false）；急诊无名氏 archiveSource=TEMP_ANONYMOUS
 * （姓名录「无名氏」）。字段与 patient 表列一一对应，敏感明文仅在本对象生命周期内存活（禁日志）。
 *
 * @param name              姓名，非空（≤64）；来源：读卡/人工录入
 * @param sex               性别字典 code，非空；来源：M01 国标字典
 * @param birthDate         出生日期（ISO yyyy-MM-dd，可空）；来源：证件/录入
 * @param ethnicity         民族字典 code（可空）
 * @param maritalStatus     婚姻状况字典 code（可空）
 * @param occupation        职业（可空）
 * @param bloodType         ABO 血型字典 code（可空）
 * @param idCardNo          身份证号原文（可空，18/15 位校验）；来源：读卡/录入，仅加密落库禁日志
 * @param mobile            手机号原文（可空，11 位校验）；同上
 * @param address           住址（可空，≤255）；同上
 * @param registerChannel   建档渠道，非空（RegisterChannel 词表）；来源：渠道端
 * @param archiveSource     档案来源（缺省 STANDARD）；来源：渠道端
 * @param identifierType    本次挂接标识类型（与 idCardNo/mobile 并行的其他介质，可空）；来源：介质选择
 * @param identifierValue   标识值原文（可空）；来源：介质读取
 * @param cardNo            卡面号（卡类介质，可空）；来源：读卡
 * @param informedConsentRef 知情同意授权依据引用，非空（建档强制采集知情同意，FU-M02-01/06）
 */
public record PatientCreateRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank String sex,
        String birthDate,
        String ethnicity,
        String maritalStatus,
        @Size(max = 32) String occupation,
        String bloodType,

        @Pattern(regexp = "(^$|^\\d{15}$|^\\d{17}[0-9Xx]$)", message = "身份证号须为 15 位或 18 位（含 X 校验位）")
        String idCardNo,

        @Pattern(regexp = "(^$|^1\\d{10}$)", message = "手机号须为 11 位数字")
        String mobile,

        @Size(max = 255) String address,

        @NotBlank @Pattern(regexp = "WINDOW|SELF_SERVICE|ONLINE|INPATIENT_REGISTER|EMERGENCY", message = "建档渠道非法")
        String registerChannel,

        @Pattern(regexp = "STANDARD|TEMP_ANONYMOUS|TEMP_NEWBORN", message = "档案来源非法")
        String archiveSource,

        @Pattern(
                regexp =
                        "ID_CARD|PASSPORT|MILITARY_OFFICER|OTHER_LEGAL|INSURANCE_ELECTRONIC|HEALTH_CARD|VISIT_CARD|MEDICAL_RECORD_NO",
                message = "标识类型非法")
        String identifierType,

        String identifierValue,
        String cardNo,
        @NotBlank String informedConsentRef) {}
