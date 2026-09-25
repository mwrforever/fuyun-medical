package com.fuyun.pharmacy.dto;

import jakarta.validation.constraints.Size;

/**
 * 审方决策入参（POST /review-tasks/{id}/approve|reject 请求体）：任务 id 由路径参数承载，
 * 请求体仅携药师意见。意见格式校验归入参（@Size 上限=review_task.opinion 列宽 512，超长
 * 400 拦截防直达 varchar(512) 触发 DB 异常 500）；「驳回必填」语义由服务端按结论执行
 * （缺意见 PH-1020，A.3 失败 ProblemDetail 统一渲染）——不挂 @NotBlank，否则 400 先拦
 * 破坏 PH-1020 冻结语义。
 *
 * @param opinion 药师意见，可空（approve 可选；reject 必填由服务端守卫），≤512 字符（列宽）；来源：审方药师工作台输入
 */
public record ReviewDecisionRequest(
        @Size(max = 512, message = "opinion 超长（≤512）") String opinion) {}
