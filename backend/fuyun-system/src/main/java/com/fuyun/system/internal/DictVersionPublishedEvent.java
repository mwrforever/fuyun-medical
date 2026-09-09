package com.fuyun.system.internal;

/**
 * 字典版本发布应用事件（Spring 应用事件，模块内事务后发布机制的触发载体，BRIEF-PR3-01 §3.2）。
 *
 * <p>发布时机：DictVersionServiceImpl.publish 事务提交前于事务上下文内发布（B.3-1 同步应用事件），
 * 由 SystemEventPublisher @TransactionalEventListener(AFTER_COMMIT) 监听——事务提交后才发 MQ
 * （A.4.2-7 事务内禁消息发送；事务回滚则事件不触发，杜绝"库未发布而广播已出"）。
 *
 * <p>落 internal/ 包：模块内事件非对外契约（对外契约是 MQ 侧 system.dict.published 信封 +
 * api 包 DictPublishedPayload），禁止外部引用（backend 宪法 B.1）。
 *
 * <p>record 纯数据载体（backend 宪法 A.1-2 透明浅不可变）。
 *
 * @param typeCode 发布的字典类型编码，非空；来源：发布事务内查得的 dict_type.type_code
 * @param version  发布的字典版本号，非空；来源：发布事务内的 dict_version.version
 */
public record DictVersionPublishedEvent(String typeCode, Integer version) {}
