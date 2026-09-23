package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 护理评估单创建入参（POST /api/v1/nursing/assessments）。assessNo 服务端发号
 * （NursingSeqGate nextNo("AS")，禁客户端传入）；assessedAt 为临床实际评估时刻（业务时间口径，
 * 非 Spec 红线 2 下的服务器时间）——服务端强校验不得晚于当前时间、不得早于该 visit 入区时间，
 * 越界拒 NS-1016；审计列以服务器时间承载，两套时钟口径在 Spec 注记登记。
 *
 * @param visitId    住院就诊号（I 型 14 位），必填；来源：操作者工作站当前患者
 * @param scaleType  量表类型 code（ScaleType 五值词表），必填，词表外值 NS-1009（CUSTOM 引擎归 P2）；
 *                   来源：评估表单选择（GET /assessment-scales 渲染）
 * @param answers    条目应答（itemCode → 分值），必填；条目缺失或取值越界 NS-1010；来源：评估表单填写
 * @param assessedAt 评估时点（临床实际评估时刻），必填；不晚于当前、不早于入区时间否则 NS-1016；
 *                   来源：操作者录入
 */
public record NursingAssessmentCreateRequest(
        @NotBlank(message = "visitId 不能为空") String visitId,
        @NotBlank(message = "scaleType 不能为空") String scaleType,
        @NotNull(message = "answers 不能为空") Map<String, Integer> answers,
        @NotNull(message = "assessedAt 不能为空") OffsetDateTime assessedAt) {}
