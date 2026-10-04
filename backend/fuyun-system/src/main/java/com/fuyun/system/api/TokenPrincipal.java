package com.fuyun.system.api;

/**
 * 令牌校验通过后的最小主体摘要（PR-4C W-39/A-2 跨模块消费面）。
 *
 * <p>与 system.record.SessionData 职责分离：本对象只暴露 WS 防线与哨兵限行所需三字段
 * （宪法 B.1 跨模块契约唯一出口=api 包，record 包会话全量状态不外泄）。
 *
 * @param userId   用户 ID，非空；哨兵为 0L（BIGSCREEN_SENTINEL_USER_ID 约定，不与雪花正数冲突）
 * @param loginName 登录名，非空；哨兵判定锚点（=="bigscreen"）
 * @param wardId   哨兵令牌绑定病区编码，可 null（登录态恒 null；泛哨兵=候诊屏 WS 用）
 */
public record TokenPrincipal(Long userId, String loginName, String wardId) {}
