package com.fuyun.system.internal;

/**
 * 权限矩阵变更应用事件（Spring 应用事件，PR-4F Task 5 定义、Task 6 消费）。
 *
 * <p>发布时机：RoleAdminServiceImpl.overwritePermissions/updateStatus 事务提交前于事务
 * 上下文内发布（B.3-1 同步应用事件，发布方必须在 Spring 事务代理内——B.2-6 防事件暂存后
 * 等不到提交信号）；消费面（Task 6 装配）经 @TransactionalEventListener(AFTER_COMMIT)
 * 监听后走 system.permission.changed 广播（V1120 integration.event_registry id 84 已登记），
 * 各实例 PermissionRegistry.load() 幂等重载 + 受影响角色会话键清理——事务回滚则事件不
 * 触发，杜绝"库未变更而刷新广播已出"（A.4.2-7 事务内禁消息发送）。
 *
 * <p>落 internal/ 包：模块内事件非对外契约（对外契约是 MQ 侧 system.permission.changed
 * 信封），禁止外部引用（backend 宪法 B.1，DictVersionPublishedEvent 同位先例）。
 *
 * <p>record 纯数据载体（backend 宪法 A.1-2 透明浅不可变）。
 *
 * @param roleCode 发生矩阵变更的角色编码，非空；来源：写事务内的路径参数 roleCode
 */
public record PermissionMatrixChangedEvent(String roleCode) {}
