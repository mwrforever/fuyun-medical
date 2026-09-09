package com.fuyun.system.api;

/**
 * 用户变更事件载荷（system.user.changed，CF-2 首批主数据事件，M01 Spec §7 明示）。
 *
 * <p>触发场景：用户档案/角色授权变更后广播，订阅方刷新会话与权限缓存（权限变更后令牌即时失效，
 * M01 Spec 边界）。事件对象定义在发布方 api 包（backend 宪法 B.3-1），字段名全集经
 * SystemMasterDataPayloadTest 线格式冻结断言。
 *
 * <p>占位 schema：正式字段随 PR-3 M01 实装冻结，event_registry 登记行 payload_desc 同步。
 *
 * <p>record 纯数据载体（backend 宪法 A.1-2 透明浅不可变）。
 *
 * @param userId     用户 ID（雪花 ID），非空；JSON 线格式经 Long→String 全局定制以字符串承载（A.3-8）
 * @param changeType 变更类型（如 CREATED/UPDATED/DEPRECATED），非空；占位取值随 PR-3 冻结
 */
public record UserChangedPayload(Long userId, String changeType) {}
