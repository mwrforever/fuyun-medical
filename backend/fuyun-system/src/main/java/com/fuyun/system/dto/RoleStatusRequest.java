package com.fuyun.system.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 角色启停请求体（PUT /api/v1/system/roles/{roleCode}/status，PR-4F Task 5）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）+ JSR-303 声明式校验（A.3-5）。
 * 值域 ACTIVE/DISABLED 的精确校验由服务端 RoleStatus.fromCode 承载（未知值收口
 * SYS-1031，code↔enum 双向映射唯一来源 A.2-7），契约层仅拦空白。
 *
 * @param status 目标状态 code（ACTIVE/DISABLED），非空白；来源：管理台启停开关提交
 */
public record RoleStatusRequest(@NotBlank String status) {}
