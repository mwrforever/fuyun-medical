package com.fuyun.system.internal;

/**
 * 执业授权变更应用事件（Spring 应用事件，practice.changed 的模块内事务后发布机制触发载体）。
 *
 * <p>发布时机：PracticeServiceImpl.grant/withdraw 事务提交前于事务上下文内发布（B.3-1 同步应用
 * 事件），由 SystemEventPublisher @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)
 * 监听——事务提交后才发 MQ（A.4.2-7 事务内禁消息发送；事务回滚则事件不触发，杜绝"库未变更而
 * 广播已出"）。
 *
 * <p>落 internal/ 包：模块内事件非对外契约（对外契约是 MQ 侧 system.practice.changed 信封 +
 * api 包 PracticeChangedPayload，V5 id 6 既有登记零新增），禁止外部引用（backend 宪法 B.1）。
 *
 * <p>record 纯数据载体（backend 宪法 A.1-2 透明浅不可变）。
 *
 * @param employeeId 员工 ID，非空；来源：grant/withdraw 事务内的授权行
 * @param grantType  授权类型词表值，非空；来源：grant/withdraw 事务内的授权行
 * @param status     变更后的授权状态（EFFECTIVE 登记/SUSPENDED 停权），非空；来源：服务端状态机
 */
public record PracticeChangedEvent(long employeeId, String grantType, String status) {}
