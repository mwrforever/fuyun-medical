package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fuyun.nursing.vo.NurseBoardPushFrame;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * 大屏 WS 推送监听器单测（Task 11）：统一信封帧 {type, payload, occurredAt} 三组件出网契约、
 * topic 前缀 /topic/nursing/board/ 拼接、发送失败 warn 降级不上抛（REST 快照兜底收敛口径）。
 */
@ExtendWith(MockitoExtension.class)
class NurseBoardPushListenerTest {

    /** 病区编码（topic 尾段断言基准） */
    private static final String WARD = "W01";

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private NurseBoardPushListener listener;

    @BeforeEach
    void setUp() {
        listener = new NurseBoardPushListener(messagingTemplate);
    }

    @Test
    @DisplayName("推送成功：统一信封帧三组件逐位出网（type/payload 原样/occurredAt），topic=前缀+wardId")
    void pushesUnifiedEnvelopeFrameToBoardTopic() {
        Instant occurredAt = Instant.parse("2026-10-02T08:30:00Z");
        NurseBoardPushFrame.OverdueTaskPayload payload =
                new NurseBoardPushFrame.OverdueTaskPayload("TK2026100200001", "TURN", null, 1, WARD);

        listener.onBoardPush(new NurseBoardPushEvent(WARD, NurseBoardPushFrame.TYPE_TASK_OVERDUE, payload, occurredAt));

        ArgumentCaptor<Object> frameCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate)
                .convertAndSend(eq(NurseBoardPushListener.BOARD_TOPIC_PREFIX + WARD), frameCaptor.capture());
        NurseBoardPushFrame frame = (NurseBoardPushFrame) frameCaptor.getValue();
        assertThat(frame.type()).isEqualTo("TASK_OVERDUE");
        assertThat(frame.payload()).isSameAs(payload);
        assertThat(frame.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("发送失败：warn 降级不上抛（提交已发生，REST 快照轮询兜底收敛）")
    void sendFailureIsSwallowedWithWarnDegradation() {
        // void 方法打桩走 doThrow 形态（convertAndSend 返回 void，when().thenReturn 不适用）
        org.mockito.Mockito.doThrow(new MessagingException("broker 不可达"))
                .when(messagingTemplate)
                .convertAndSend(any(String.class), any(Object.class));

        assertThatCode(() -> listener.onBoardPush(new NurseBoardPushEvent(
                        WARD,
                        NurseBoardPushFrame.TYPE_BED_PATIENT,
                        new NurseBoardPushFrame.BedPatientPayload("I2026100200001", 7001L, "01", WARD),
                        Instant.now())))
                .as("推送失败不得上抛（AFTER_COMMIT 时点事务已提交，上抛无法回滚且污染调用方）")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("topic 前缀契约：/topic/nursing/board/（brief 冻结形态锚）+ type 词表五值冻结互异")
    void boardTopicPrefixIsFrozenContract() {
        assertThat(NurseBoardPushListener.BOARD_TOPIC_PREFIX).isEqualTo("/topic/nursing/board/");
        // type 词表五值冻结锚（派发上下文 §3——前端 Task 17 收窄词表；互异防词表内撞名）
        List<String> vocabulary = List.of(
                NurseBoardPushFrame.TYPE_BED_PATIENT,
                NurseBoardPushFrame.TYPE_TASK_OVERDUE,
                NurseBoardPushFrame.TYPE_INFUSION_ESCALATION,
                NurseBoardPushFrame.TYPE_ADVERSE_EVENT_REMIND,
                NurseBoardPushFrame.TYPE_CALL_TRIGGERED);
        assertThat(vocabulary)
                .containsExactly(
                        "BED_PATIENT", "TASK_OVERDUE", "INFUSION_ESCALATION", "ADVERSE_EVENT_REMIND", "CALL_TRIGGERED");
    }
}
