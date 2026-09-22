package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * 体征录入入参（POST /api/v1/nursing/vital-signs，手工/PDA 点测）。patientId/wardId/measuredAt
 * 不设入参组件：patient/ward 由 IWardMetaService 在区行服务端装配（不信客户端）；测量时点
 * 业务时间一律服务器时间（GC25）。各指标均可空（点测允许只测部分项目），全部为空由前端
 * 约束（服务端不限——非体温项录入合法）。生理极限校验在服务端（NS-1005 带医学文案，非
 * Bean Validation 通用文案）。
 *
 * @param visitId     住院就诊号（I 型 14 位），必填；来源：操作者工作站/PDA 当前患者
 * @param source      数据源 code（MANUAL/PDA；空缺省 MANUAL；IOT 归 P2 拒收 NS-1019），可空；来源：PDA 端标识/工作站默认
 * @param temperature 体温（℃），可空；来源：测量值录入
 * @param tempSite    体温部位 code（ORAL/AXILLARY/RECTAL；非法值 NS-1019），可空；来源：测量方式选择
 * @param pulse       脉搏（次/分），可空；来源：测量值录入
 * @param respiration 呼吸（次/分），可空；来源：测量值录入
 * @param systolicBp  收缩压（mmHg），可空；来源：测量值录入
 * @param diastolicBp 舒张压（mmHg），可空；来源：测量值录入
 * @param spo2        血氧饱和度（%），可空；来源：测量值录入
 * @param weight      体重（kg，不参与阈值判定），可空；来源：测量值录入
 * @param height      身高（cm，不参与阈值判定），可空；来源：测量值录入
 * @param painScore   疼痛评分（NRS 0-10），可空；来源：患者主诉评估录入
 */
public record VitalSignRecordRequest(
        @NotBlank(message = "visitId 不能为空") String visitId,
        String source,
        BigDecimal temperature,
        String tempSite,
        Integer pulse,
        Integer respiration,
        Integer systolicBp,
        Integer diastolicBp,
        Integer spo2,
        BigDecimal weight,
        BigDecimal height,
        Integer painScore) {}
