package com.fuyun.system.record;

import com.fuyun.system.constants.SecurityConstants;

/**
 * 令牌 payload 线格式载体（D-2 两段式 HMAC 令牌，非 JWT claims 集，BRIEF-PR3-01 §1.1）。
 *
 * <p>record 组件名即紧凑 JSON 的短键（uid/eid/oid/sid/typ/exp），与 {@link SecurityConstants}
 * 的 CLAIM_* 常量一一对应（线格式经 TokenServiceImplTest 冻结断言，改动任一侧须同步并重放测试）。
 * 序列化形态：{@code {"uid":"123...","eid":"456...","oid":null,"sid":"<UUID>","typ":"access","exp":1730000000000}}
 *
 * @param uid 用户 ID，雪花 ID 十进制字符串，非空；来源：登录成功后的 sys_user.id
 * @param eid 员工 ID 十进制字符串，可 null（系统/接口账号无员工）；来源：sys_employee.user_id 反查
 * @param oid 主归属机构 ID 十进制字符串，可 null（P0 种子不建 org 行）；来源：sys_employee.primary_org_id
 * @param sid 会话标识 UUID，非空；= Redis 会话键 fy:system:session:{sid} 尾段，签发时生成
 * @param typ 令牌类型，非空；access/refresh 二值，校验端严格匹配（见 SecurityConstants.TOKEN_TYPE_*）
 * @param exp 过期时刻 epoch 毫秒，非空；来源：签发时刻 + TTL（access/refresh 各自 TTL）
 */
public record TokenClaims(String uid, String eid, String oid, String sid, String typ, long exp) {}
