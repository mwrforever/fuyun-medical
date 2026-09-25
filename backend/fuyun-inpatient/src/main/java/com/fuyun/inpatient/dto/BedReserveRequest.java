package com.fuyun.inpatient.dto;

/**
 * 床位预占入参（POST /api/v1/inpatient/beds/{id}/reserve）：FREE→RESERVED 预占入口
 * （登记台签床/转科预占/全院一张床跨病区签床共用）。预占不绑定就诊主体——visit_id 在入院
 * 登记确认才签发，占床绑定由 assign/入科确认承载，故请求体无业务字段（床位 id 在路径、
 * 操作者由登录上下文承载），保留空 record 以固化端点契约与 OpenAPI 请求体形状。
 */
public record BedReserveRequest() {}
