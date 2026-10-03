package com.fuyun.nursing.internal;

import com.fuyun.nursing.vo.NurseBoardPushFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 护士站大屏 WS 推送监听器（Task 11——四推送点统一执行面）：事务提交后（AFTER_COMMIT）经
 * {@link SimpMessagingTemplate} 向内存 SimpleBroker 推送
 * {@code /topic/nursing/board/{wardId}} 统一信封帧（{@link NurseBoardPushFrame} 冻结形态）。
 *
 * <p>推送点：床位患者动态（InpatientVisitEventListener 投影四路）、任务逾期
 * （TaskOverdueServiceImpl tick 段①②）、输注升级强提醒（InfusionServiceImpl 升级动作）、
 * 不良事件超时提醒（AdverseEventServiceImpl tick 搭载段）、设备呼叫转发
 * （IotCallTriggeredListener）。同步监听（提交线程顺执，零额外延迟面）；无事务发布点
 * （MQ 消费线程零事务路径）经 fallbackExecution 立即触发（QueueCalledPushListener 同款）。
 *
 * <p>推送异常仅记 warn 不上抛：提交已发生，上抛无法回滚事务且污染调用方语义（MQ 消费侧上抛
 * 会引发无效重投）；失败由大屏 REST 快照轮询通道兜底收敛（10s 轮询惯例）。线程安全：无状态
 * 单例。Bean 注册点 NursingWebSocketConfig @Import。
 */
@Slf4j
public class NurseBoardPushListener {

    /** 大屏 board 主题前缀（brief 冻结：/topic/nursing/board/{wardId}） */
    public static final String BOARD_TOPIC_PREFIX = "/topic/nursing/board/";

    /** STOMP 发送模板：SimpleBroker 通道唯一发送口（来源：@EnableWebSocketMessageBroker 基础设施） */
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 全参构造器（装配归 NursingWebSocketConfig @Import，backend 宪法 B.1）。
     *
     * @param messagingTemplate STOMP 消息模板，非空；来源：@EnableWebSocketMessageBroker 派生 Bean
     */
    public NurseBoardPushListener(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 事务提交后大屏推送：统一信封 {type, payload, occurredAt} 纯 JSON（前端纯函数收窄入参），
     * topic 尾段=事件携带路由病区。发送失败 warn 降级（REST 兜底收敛），不上抛。
     *
     * @param event 大屏推送事件（发布点组装完成的全部帧要素），非空
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBoardPush(NurseBoardPushEvent event) {
        String topic = BOARD_TOPIC_PREFIX + event.wardId();
        try {
            // 消息发送：提交后执行（事务内禁推送红线；帧信封冻结形态出网）
            messagingTemplate.convertAndSend(
                    topic, new NurseBoardPushFrame(event.type(), event.payload(), event.occurredAt()));
            log.info("护士站大屏 WS 提交后推送完成：topic={}，type={}", topic, event.type());
        } catch (RuntimeException e) {
            // 推送失败不阻断：AFTER_COMMIT 时点事务已提交（MQ 侧零事务亦无回滚语义），上抛引发
            // 无效重投或污染提交线程；断连/瞬时故障由 REST 快照轮询兜底收敛
            log.warn("护士站大屏 WS 推送失败（REST 快照兜底收敛）：topic={}，type={}，原因={}", topic, event.type(), e.getMessage());
        }
    }
}
