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
 */
public record UserVO(Long userId, String loginName, String displayName, Long orgId, List<String> roles) {}
