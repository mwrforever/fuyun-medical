package com.fuyun.billing.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 退费分级参数（Spec §6 FU-M13-03 阈值走系统参数不写死；M01 参数中心热刷新随其版本化读接口就绪接入，
 * 本 PR 经 env 注入默认值，W-8 同款前置登记）。
 *
 * @param autoExemptFen      当日更正免审直退阈值（分），默认 50000（≤此额免审）
 * @param singleApprovalFen  一级审批上限（分），超此走二级财务/医保办
 */
@ConfigurationProperties(prefix = "fuyun.billing.refund")
public record BillingRefundProperties(
        @DefaultValue("50000") long autoExemptFen,
        @DefaultValue("200000") long singleApprovalFen) {}
