package com.fuyun.nursing.internal;

import java.time.Instant;

/**
 * 护士站大屏 WS 推送模块内应用事件（Task 11，A.4.2-7 事务内禁消息发送红线的进程内桥）：
 * 各推送点在事务内（或无事务上下文）发布本事件，{@link NurseBoardPushListener} 于事务提交后
 * （AFTER_COMMIT）执行 /topic/nursing/board/{wardId} STOMP 推送——事务回滚则事件不触达，
 * 杜绝大屏先于库态展示；无事务发布点（MQ 消费线程零事务路径）经 fallbackExecution 立即触发
 * （QueueCalledPushEvent 同型范式，brief「事务内禁推送」原文承载）。
 *
 * <p>载荷在发布点组装完成（含定位键），监听器零额外查询；type 词表与载荷形态归
 * {@code NurseBoardPushFrame}（前端 Task 17 消费契约冻结面）。
 *
 * @param wardId    路由病区（topic 尾段——护理病区编码或 CALL_TRIGGERED 的 iot 病区 id 数字串，
 *                  见 NurseBoardPushFrame javadoc 标识空间注记），非空
 * @param type      帧类型（NurseBoardPushFrame.TYPE_* 词表），非空
 * @param payload   类型化载荷（NurseBoardPushFrame 嵌套 record），非空
 * @param occurredAt 事件发生时刻（发布点事件时点，非推送时点），非空
 */
public record NurseBoardPushEvent(String wardId, String type, Object payload, Instant occurredAt) {}
