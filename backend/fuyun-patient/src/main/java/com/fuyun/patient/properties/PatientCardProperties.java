package com.fuyun.patient.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 一卡通启用参数（M02 §4 card_account「可选启用，系统参数默认关闭」；fuyun.patient.card 前缀）。
 *
 * @param accountEnabled 是否启用一卡通账户（false=发卡不建账户）；来源：env FUYUN_PATIENT_CARD_ACCOUNT_ENABLED
 */
@ConfigurationProperties(prefix = "fuyun.patient.card")
public record PatientCardProperties(@DefaultValue("false") boolean accountEnabled) {}
