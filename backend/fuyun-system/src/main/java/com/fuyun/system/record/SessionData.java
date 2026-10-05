package com.fuyun.system.record;

import java.util.List;

/**
 * Redis 登录会话状态（键 fy:system:session:{sid} 的 JSON 值，BRIEF-PR3-01 §1.2）。
 *
 * <p>角色摘要存会话不进令牌体（M01 Spec §5"令牌含角色摘要"语义由 sid→会话承载，D-2 裁决口径）：
 * 令牌只锚定 sid，角色变更经踢出（删键）后重新登录生效。JSON 序列化经 StringRedisTemplate
 * 承载（Key/Value 均 String，禁 JDK 序列化）；会话键必有 TTL（access TTL 滑动续期）。
 *
 * @param userId      用户 ID，非空；verify 成功后经拦截器注入 OperatorContextHolder（十进制字符串化）
 * @param loginName   登录名，非空；审计与排障锚点
 * @param displayName 显示名，非空；前端用户区展示
 * @param employeeId  员工 ID，可 null（系统/接口账号无员工）
 * @param orgId       主归属机构 ID，可 null
 * @param roles       角色编码清单，非 null（无角色为空清单）；403 鉴权拦截（P1）的数据来源
 * @param wardId      大屏哨兵令牌绑定的病区编码，可 null（登录态恒 null；哨兵经 bigscreen-token?wardId= 透传，REST 限行与 WS 订阅防线比对源）
 * @param permissions 角色展开的授权点编码清单（MENU+API 全命名空间），非 null（无权限为空清单）；旧会话 JSON 缺该
 *                    字段时反序列化为 null（升级窗口内既有会话，PR-4D 加字段），消费侧须归一空清单守卫
 *                    （AuthService.refresh 已落位，r1 §3.4 record 缺字段先例）
 */
public record SessionData(
        Long userId,
        String loginName,
        String displayName,
        Long employeeId,
        Long orgId,
        List<String> roles,
        String wardId,
        List<String> permissions) {}
