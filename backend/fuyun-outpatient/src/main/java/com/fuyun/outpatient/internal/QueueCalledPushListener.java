package com.fuyun.outpatient.internal;

import com.fuyun.outpatient.vo.QueueCalledNotice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 叫号 WS 推送监听器（BUG-04 修复）：叫号/重呼事务提交后（AFTER_COMMIT）执行双 topic STOMP
 * 推送（端到端 ≤2s，Spec :198）——事务内仅发布 {@link QueueCalledPushEvent}，推送点后移出
 * 事务边界（A.4.2-7 事务内禁止消息发送；OutpatientEventPublisher 同款 AFTER_COMMIT 范式，
 * WS 通道对 MQ 通道）。同步监听（提交线程顺执，零额外延迟面）；推送异常仅记日志不上抛
 * （提交已发生，上抛无法回滚事务且污染调用方语义），失败由大屏轮询快照通道兜底收敛。
 * 线程安全：无状态单例。Bean 注册点 OutpatientWebSocketConfig @Import。
 */
@Slf4j
public class QueueCalledPushListener {

    /** 诊区队列 topic 前缀（大屏/语音客户端订阅面，Spec :179） */
    private static final String QUEUE_TOPIC_PREFIX = "/topic/outpatient/queue/";

    /** 医生站 topic 前缀（医生站提醒订阅面，Spec :179） */
    private static final String DOCTOR_TOPIC_PREFIX = "/topic/outpatient/doctor/";

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 全参构造器（装配归 OutpatientWebSocketConfig @Import，backend 宪法 B.1）。
     *
     * @param messagingTemplate STOMP 消息模板，非空；@EnableWebSocketMessageBroker 派生 Bean，
     *                          双 topic 叫号推送
     */
    public QueueCalledPushListener(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 事务提交后双 topic 推送：诊区队列 topic（大屏/语音）+医生 topic（医生站提醒），
     * 载荷=事件携带的已组装通知（脱敏口径不变：ticketNo+姓名掩码）。无事务发布点（理论不触达，
     * 叫号/重呼恒 @Transactional）经 fallbackExecution 立即触发，与发布器 D-8 兜底同口径。
     *
     * @param event 叫号推送事件（通知+双 topic 尾段），非空
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueueCalled(QueueCalledPushEvent event) {
        QueueCalledNotice notice = event.notice();
        // 消息发送：双 topic 推送（提交后执行；队列快照 REST 之外的实时通道）
        messagingTemplate.convertAndSend(QUEUE_TOPIC_PREFIX + event.deptCode(), notice);
        messagingTemplate.convertAndSend(DOCTOR_TOPIC_PREFIX + event.doctorId(), notice);
        log.info(
                "叫号 WS 提交后推送完成：queueTopic={}，doctorTopic={}，ticketNo={}",
                QUEUE_TOPIC_PREFIX + event.deptCode(),
                DOCTOR_TOPIC_PREFIX + event.doctorId(),
                notice.ticketNo());
    }
}
