package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 疑似重复排除请求（POST /possible-duplicates/{id}/exclude；理由必填——M02 §5 状态机）。
 *
 * @param note 排除理由，非空（review_note 落痕）
 */
public record ExcludeRequest(@NotBlank String note) {}
