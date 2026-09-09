package com.fuyun.system.vo;

import java.time.OffsetDateTime;

/**
 * 执业授权校验响应出参（POST /api/v1/system/practice/check，BRIEF-PR3-01 §3.3）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）。P0 为骨架响应：passed 恒 false；
 * 响应契约自 P0 冻结，P1 真实校验（practice_grant 表）替换内部实现时此 VO 不变。
 *
 * @param employeeId 员工 ID（请求入参回显）；Long 经全局 Long→String 定制以 JSON 字符串输出
 * @param grantType  授权类型（请求入参回显），非空
 * @param checkTime  校验时点（请求缺省时为服务端当前时刻），非空
 * @param passed     是否通过授权校验；P0 骨架恒 false（真实校验随 P1 执业授权库表启用）
 * @param reason     校验结论说明，非空；P0 为占位文案
 */
public record PracticeCheckResponse(
        String employeeId, String grantType, OffsetDateTime checkTime, boolean passed, String reason) {}
