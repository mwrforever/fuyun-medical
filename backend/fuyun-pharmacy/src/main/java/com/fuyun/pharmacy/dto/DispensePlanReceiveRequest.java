package com.fuyun.pharmacy.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 摆药计划病区签收入参（POST /api/v1/pharmacy/dispense-plans/{no}/receive，P2 PR-3 Task 8）。
 * 签收人由请求体显式携带（病区护士工号员工 ID）而非登录上下文推断——签收主体为病区侧
 * 责任人，与药房侧操作者（ pharmacist）分权留痕。
 *
 * @param receivedBy 病区签收人员工 ID，必填；来源：病区签收确认提交
 */
public record DispensePlanReceiveRequest(@NotNull Long receivedBy) {}
