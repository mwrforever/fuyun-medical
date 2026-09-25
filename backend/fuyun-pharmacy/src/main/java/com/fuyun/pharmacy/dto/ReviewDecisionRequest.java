package com.fuyun.pharmacy.dto;

/**
 * 审方决策入参（POST /review-tasks/{id}/approve|reject 请求体）：任务 id 由路径参数承载，
 * 请求体仅携药师意见。意见格式校验归入参（长度上限=列宽 512）；「驳回必填」语义由服务端
 * 按结论执行（缺意见 PH-1020，A.3 失败 ProblemDetail 统一渲染）——不挂 @NotBlank，否则
 * 400 先拦破坏 PH-1020 冻结语义。
 *
 * @param opinion 药师意见，可空（approve 可选；reject 必填由服务端守卫）；来源：审方药师工作台输入
 */
public record ReviewDecisionRequest(String opinion) {}
