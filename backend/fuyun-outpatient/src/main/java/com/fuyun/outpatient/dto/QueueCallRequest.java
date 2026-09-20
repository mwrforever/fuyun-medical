package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 叫号请求（POST /queue/call，M03 分诊台/医生站叫号入口）：按 ZSET 优先级序出队首个「未指派或
 * 指派一致」的候诊票。叫号≠接诊——visit 保持 WAITING，接诊由 /visits/{visitId}/admit 承载。
 *
 * @param deptCode 队列标识（=dept_code 诊区队列），非空白；来源：分诊台当前队列
 * @param doctorId 叫号医生 id，非空白；来源：登录医生/分诊台指定
 */
public record QueueCallRequest(
        @NotBlank(message = "deptCode 不得为空白") String deptCode,
        @NotBlank(message = "doctorId 不得为空白") String doctorId) {}
