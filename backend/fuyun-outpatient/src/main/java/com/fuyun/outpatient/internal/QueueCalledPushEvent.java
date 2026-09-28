package com.fuyun.outpatient.internal;

import com.fuyun.outpatient.vo.QueueCalledNotice;

/**
 * 叫号 WS 推送模块内应用事件（BUG-04 修复，A.4.2-7 事务内禁消息发送红线的进程内桥）：
 * 叫号/重呼事务内发布本事件，{@link QueueCalledPushListener} 于事务提交后（AFTER_COMMIT）
 * 执行双 topic STOMP 推送——事务回滚则事件不触达，杜绝大屏/医生站先于库态展示「已叫号」。
 * 载荷在事务内组装完成（含脱敏名/诊室两跳读），监听器零额外查询（BillingDomainEvent 同型范式）。
 *
 * @param notice   已组装叫号通知（脱敏载荷：ticketNo+姓名掩码，无原始标识），非空；来源：分诊服务事务内组装
 * @param deptCode 诊区队列标识（大屏 topic 尾段），非空；来源：叫号请求或票据 queue_id
 * @param doctorId 叫号医生 id（医生站 topic 尾段），非空；来源：叫号请求或票指派医生回落操作者
 */
public record QueueCalledPushEvent(QueueCalledNotice notice, String deptCode, String doctorId) {}
