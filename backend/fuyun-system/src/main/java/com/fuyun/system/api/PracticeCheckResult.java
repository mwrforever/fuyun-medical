package com.fuyun.system.api;

/**
 * 执业授权校验结果（PracticeCheckPort.check 返回投影，record 透明浅不可变载体——backend 宪法
 * A.1-2）：IPracticeService.check 响应的 passed/reason 双组件原样透传，禁二次包装。
 *
 * @param passed 是否通过授权校验（EFFECTIVE 且有效期含校验日）
 * @param reason 校验结论说明（未过的两态文案：授权已过期 {grantType} / 无有效执业授权记录
 *               {grantType}；消费方拼入业务异常 message 出 ProblemDetail）
 */
public record PracticeCheckResult(boolean passed, String reason) {}
