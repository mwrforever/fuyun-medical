package com.fuyun.nursing.constants;

import java.math.BigDecimal;

/**
 * 体征阈值判定入参（Global Constraints 19 冻结面；Task 5 体征域装配，NursingVitalThresholds
 * 三静态判定共用载体）。仅承载参与阈值判定的指标：体重/身高不参与阈值判定（无正常范围与
 * 生理极限约束），故不纳入本 record——体重身高只随 vital_sign_record 落库展示。
 *
 * @param temperature 体温（℃），可空（未测项不参与判定）；来源：体征录入表单
 * @param tempSite    体温部位 code（ORAL/AXILLARY/RECTAL），可空；随体温值携带（体温单条目键与
 *                    符号渲染依据；Global Constraints 19 正常范围未分部位，判定不区分），可空
 * @param pulse       脉搏（次/分），可空（未测不参与判定）
 * @param respiration 呼吸（次/分），可空（未测不参与判定）
 * @param systolicBp  收缩压（mmHg），可空（未测不参与判定）
 * @param diastolicBp 舒张压（mmHg），可空（未测不参与判定）
 * @param spo2        血氧饱和度（%），可空（未测不参与判定）
 * @param painScore   疼痛评分（NRS 0-10），可空（未评不参与判定）
 */
public record VitalSignValues(
        BigDecimal temperature,
        String tempSite,
        Integer pulse,
        Integer respiration,
        Integer systolicBp,
        Integer diastolicBp,
        Integer spo2,
        Integer painScore) {}
