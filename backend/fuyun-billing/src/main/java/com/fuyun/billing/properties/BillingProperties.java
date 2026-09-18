package com.fuyun.billing.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 收费域通用参数（M01 参数中心热刷新就绪前经 env 注入兜底，W-8 前置登记同款；
 * Bean 注册归 fuyun-app 侧 config 装配——Task 16 交付，本类只承载参数形态）。
 *
 * @param depositWarningThresholdFen 住院预交金欠费预警默认阈值（分），默认 10000（即 100 元）——
 *                                   开户未显式传阈值时的缺省；有效余额（余额−已确认未结算费用）
 *                                   低于该阈值判欠费（ARREARS，Spec §6 FU-M13-04）
 */
@ConfigurationProperties(prefix = "fuyun.billing")
public record BillingProperties(@DefaultValue("10000") long depositWarningThresholdFen) {}
