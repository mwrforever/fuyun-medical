package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 入区登记入参（POST /api/v1/nursing/ward-patients，P1 过渡通道——「临时（P1 过渡）」载体，
 * P2 由 inpatient.visit.* + bed.changed 事件链替代后整体退役）。
 *
 * <p>幂等 upsert：同 visit_id 已在区时本请求语义为「视图属性更新」（床位/护理级别等），
 * 不新建行；admittedAt 不设入参组件——补录入院时间属 ADT 写能力（端点冻结清单禁项），
 * 服务端一律取登记时点服务器时间。
 *
 * @param visitId       住院就诊号（I 型 14 位，string 承载；结构校验 NS-1003），必填；来源：操作者自 M04 入科单誊抄
 * @param patientId     患者主索引（经 PatientContextResolver 归一/拦截，FROZEN NS-1004），必填；来源：操作者自患者卡誊抄
 * @param wardId        病区编码，必填；来源：操作者当前工作站病区
 * @param bedNo         床位号（在区态病区内唯一，冲突 NS-1002），必填；来源：操作者
 * @param patientName   患者展示名（V801 NOT NULL 列），必填；来源：操作者录入
 * @param gender        性别 code（M01 字典），可空；来源：操作者录入
 * @param age           年龄（岁），可空；来源：操作者录入
 * @param nursingLevel  护理级别（NursingLevel code，空缺省 NORMAL；非法值 NS-1019），可空；来源：操作者录入
 * @param conditionTags 病情状态标记（逗号分隔展示镜像），可空；来源：操作者录入
 */
public record WardPatientRegisterRequest(
        @NotBlank(message = "visitId 不能为空") String visitId,
        @NotNull(message = "patientId 不能为空") Long patientId,
        @NotBlank(message = "wardId 不能为空") String wardId,
        @NotBlank(message = "bedNo 不能为空") String bedNo,
        @NotBlank(message = "patientName 不能为空") String patientName,
        String gender,
        Integer age,
        String nursingLevel,
        String conditionTags) {}
