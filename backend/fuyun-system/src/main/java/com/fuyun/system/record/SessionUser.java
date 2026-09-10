package com.fuyun.system.record;

import java.util.List;

/**
 * 登录会话输入载体（B3.2 登录用例组装后交令牌服务签发，BRIEF-PR3-01 §3.1 ITokenService 契约）。
 *
 * <p>与 {@link SessionData} 字段同构但职责分离（A.7-3：请求与输出分别建模）——本对象是"认证成功瞬间"
 * 的入参，SessionData 是落 Redis 并经 verify 返回的会话状态。
 *
 * @param userId      用户 ID（sys_user.id 雪花 ID），非空；来源：findByLoginName 查询
 * @param loginName   登录名，非空；来源：登录请求原文（大小写以库内存量为准）
 * @param displayName 显示名（员工姓名或登录名兜底），非空；来源：sys_employee.emp_name
 * @param employeeId  员工 ID（sys_employee.id），可 null（系统/接口账号无员工）
 * @param orgId       主归属机构 ID，可 null（P0 种子不建 org 行）
 * @param roles       角色编码清单（如 ["ADMIN"]），非 null（无角色为空清单）；来源：user_role→role 两步单表查询
 */
public record SessionUser(
        Long userId, String loginName, String displayName, Long employeeId, Long orgId, List<String> roles) {}
