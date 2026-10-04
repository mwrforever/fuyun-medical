package com.fuyun.pharmacy.dto;

/**
 * 摆药计划病区签收入参（POST /api/v1/pharmacy/dispense-plans/{no}/receive，P2 PR-3 Task 8）。
 * W-72（PR-4B）：receivedBy 仅为兼容保留字段（可传可不传，服务端不消费）——签收人一律
 * 以登录令牌身份落值，签收主体留痕不可由请求体指定。
 *
 * @param receivedBy 病区签收人员工 ID，兼容保留（服务端忽略——落值=令牌身份），可空
 */
public record DispensePlanReceiveRequest(Long receivedBy) {}
