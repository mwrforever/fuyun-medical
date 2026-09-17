package com.fuyun.patient.vo;

import java.util.Map;

/**
 * 明文查阅出参（POST /privacy/unmask 响应体即明文出口）：仅角色豁免校验通过可达，
 * 双留痕（M01 审计 SENSITIVE_QUERY 行 + privacy_access_log 台账行）。
 *
 * @param patientId 被查阅患者主索引
 * @param values    明文值集：键=请求字段词（PrivacyConstants.TARGET_*），值=对应明文（解密列经构件解出）
 */
public record UnmaskVO(long patientId, Map<String, String> values) {}
