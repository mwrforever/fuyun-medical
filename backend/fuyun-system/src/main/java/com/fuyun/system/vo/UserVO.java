package com.fuyun.system.vo;

import java.util.List;

/**
 * 登录用户身份出参（LoginResponse.user 字段，BRIEF-PR3-01 §1.3）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）。userId/orgId 为 Long，经全局
 * Long→String 定制（JacksonLongToStringConfig）以 JSON 字符串输出，前端以字符串承载
 * （雪花 ID 越出 JS Number.MAX_SAFE_INTEGER 防线，A.3-8）。
 *
 * @param userId      用户 ID（sys_user.id 雪花 ID），非空；JSON 输出为字符串
 * @param loginName   登录名，非空
 * @param displayName 显示名（员工姓名，无员工行以登录名兜底），非空；前端用户区展示
 * @param orgId       主归属机构 ID，可 null（P0 种子不建机构行）；JSON 输出为字符串或 null
 * @param roles       角色编码清单，非 null（无角色为空清单）；403 鉴权拦截（P1）的数据来源
 * @param permissions 权限点编码清单（角色展开后的授权点集），非 null；P0 权限点体系未建恒为
 *                    空集合——前端守卫骨架（BUG-14）按「空集 = 全放行」口径兼容；P1 收紧为
 *                    「空集 = 无任何权限」时的数据来源
 */
// TODO(P1-authz): 权限点体系接线后按角色导出权限点集合（当前登录链路填充空集合占位，计划 P1 版本引入）
public record UserVO(
        Long userId, String loginName, String displayName, Long orgId, List<String> roles, List<String> permissions) {}
