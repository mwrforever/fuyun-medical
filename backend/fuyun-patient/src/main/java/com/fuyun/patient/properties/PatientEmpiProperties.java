package com.fuyun.patient.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * EMPI 匹配引擎参数（M02 Spec FU-M02-02：匹配规则阈值系统参数化可调；fuyun.patient.empi 前缀）。
 *
 * <p>调优口径：threshold 为弱标识评分进入「疑似重复待审」的下限（0-100）；评分规则代码内固定
 * （NAME_SEX_BIRTH=95 / NAME_MOBILE=90 / NAME_SEX=70 / NAME_ONLY=60 / 强标识矛盾=100），
 * 仅阈值随院内数据质量调优（简单优先，不提前参数化规则表）。
 *
 * @param suspectThreshold 疑似重复评分阈值（缺省 85）；来源：env FUYUN_PATIENT_EMPI_SUSPECT_THRESHOLD
 */
@ConfigurationProperties(prefix = "fuyun.patient.empi")
public record PatientEmpiProperties(@DefaultValue("85") int suspectThreshold) {}
