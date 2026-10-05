package com.fuyun.outpatient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.properties.OutpatientProperties;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.ApplicationArguments;

/**
 * 号源超时 tick 启动播种器与发送器单测（P2 PR-4E Task 8，W-27；nursing
 * TaskOverdueTickSeederTest 克隆基准，RabbitTemplate mock 循同款范式）：播种器四分支锁启动
 * 可用性面——开关断电整体跳过（tickSelfRearm=false 先于积压判定）、档位积压幂等跳过（链路在飞
 * 不双点燃）、空档位点燃恰一次、播种失败吞异常不阻断启动（MQ 瞬断不拖垮门诊业务启动，可经
 * 重启/运维补发自愈）；发送器锁发号路由锚——fy.delay 延迟档位 delay.appointment-timeout-tick
 * 空载荷 ping 帧携随机 CorrelationData 定位锚，与档位积压计数三态防御（0/正数/null 查询口径，
 * messageCount 零消费被动查询）。
 */
@ExtendWith(MockitoExtension.class)
class AppointmentTimeoutTickSeederTest {

    @Mock
    private AppointmentTimeoutTickSender tickSender;

    @Mock
    private RabbitTemplate rabbitTemplate;

    /** 启动参数替身（播种器不消费，仅满足 ApplicationRunner 契约） */
    @Mock
    private ApplicationArguments arguments;

    @Test
    @DisplayName("开关断电时不播种：tickSelfRearm=false 启动播种整体跳过（发送器零触达）")
    void disabledSelfRearmSkipsSeedingEntirely() {
        AppointmentTimeoutTickSeeder seeder = new AppointmentTimeoutTickSeeder(tickSender, selfRearm(false));

        seeder.run(arguments);

        // 断电先于积压判定：开关关闭时连惰性积压查询都不发起（严格于 never sendTick——零交互）
        verifyNoInteractions(tickSender);
    }

    @Test
    @DisplayName("档位积压时幂等跳过：链路在飞不双点燃（首帧不投递）")
    void pendingBacklogSkipsSeedingIdempotently() {
        AppointmentTimeoutTickSeeder seeder = new AppointmentTimeoutTickSeeder(tickSender, selfRearm(true));
        when(tickSender.hasPendingTick()).thenReturn(true);

        seeder.run(arguments);

        // 幂等守卫：档位已有待到期帧即跳过播种，多实例竞争窗口经 TTL 自然对齐无害
        verify(tickSender, never()).sendTick();
    }

    @Test
    @DisplayName("空档位时点燃恰一次：首条 tick 投递点燃自续期心跳链路")
    void emptySlotIgnitesSelfRearmChainExactlyOnce() {
        AppointmentTimeoutTickSeeder seeder = new AppointmentTimeoutTickSeeder(tickSender, selfRearm(true));
        when(tickSender.hasPendingTick()).thenReturn(false);

        seeder.run(arguments);

        // 恰一次语义：空档位判定通过后仅投递一帧（此后链路自我维持，进程存活即有心跳）
        verify(tickSender, times(1)).sendTick();
    }

    @Test
    @DisplayName("播种失败不阻断启动：sendTick 抛异常被吞仅留痕（MQ 瞬断自愈口径）")
    void seedingFailureSwallowedWithoutBlockingStartup() {
        AppointmentTimeoutTickSeeder seeder = new AppointmentTimeoutTickSeeder(tickSender, selfRearm(true));
        when(tickSender.hasPendingTick()).thenReturn(false);
        doThrow(new RuntimeException("MQ 瞬断模拟")).when(tickSender).sendTick();

        // 可用性口径：播种失败不上抛——应用照常启动，链路缺失仅影响超时兜底扫描时效（可重启/补发自愈）
        assertThatCode(() -> seeder.run(arguments)).doesNotThrowAnyException();
        verify(tickSender).sendTick();
    }

    @Test
    @DisplayName("发送路由面锁定：fy.delay→delay.appointment-timeout-tick 空载荷 ping 帧携随机 CorrelationData")
    void sendTickLocksRoutingFaceOnDelaySlot() {
        AppointmentTimeoutTickSender sender = new AppointmentTimeoutTickSender(rabbitTemplate);

        sender.sendTick();

        // 路由锚：延迟档位名即路由键（fy.delay 主题交换机绑定队列名），空串 ping 载荷——自续期
        // 链路只需存在性不需内容；CorrelationData 携随机 UUID（共享确认回调失败帧定位锚）
        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate)
                .convertAndSend(
                        eq(MessagingConstants.EXCHANGE_DELAY),
                        eq(OutpatientMessagingConstants.DELAY_QUEUE_APPOINTMENT_TIMEOUT_TICK),
                        eq(""),
                        correlationCaptor.capture());
        assertThat(correlationCaptor.getValue().getId()).isNotBlank();
    }

    @Test
    @DisplayName("队列计数三态防御：0/正数→无积压/有积压，null 未知态按无积压处理")
    void messageCountThreeStateDefense() throws IOException {
        // 正常口径：execute 回调真打档位计数（messageCount 零消费被动查询），0 无积压/正数在飞
        assertThat(pendingTickJudgedFromChannel(0L)).isFalse();
        assertThat(pendingTickJudgedFromChannel(3L)).isTrue();

        // 防御口径：execute 整体返回 null（模板层未知态）按无积压处理——播种侧宁可重播种也不饿死链路
        RabbitTemplate nullReturnTemplate = mock(RabbitTemplate.class);
        when(nullReturnTemplate.execute(any())).thenReturn(null);
        assertThat(new AppointmentTimeoutTickSender(nullReturnTemplate).hasPendingTick())
                .isFalse();
    }

    /**
     * 经真实 execute 回调构造积压判定：回调内 messageCount 打在冻结延迟档位名上
     * （strict stubs 下档位名不符即失配报错——计数查询目标被隐式锁定）。每场景独立替身，
     * 避免同一模板上重设桩时旧 Answer 命中打桩期空调用（matcher 占位 null）。
     *
     * @param channelCount 档位内待到期帧数（amqp-client 5.x messageCount 返回 long）
     * @return 播种器视角的积压判定结果（true=链路在飞）
     * @throws IOException 仅声明以满足 messageCount 受检签名（替身桩运行期不实际抛出）
     */
    private boolean pendingTickJudgedFromChannel(long channelCount) throws IOException {
        RabbitTemplate template = mock(RabbitTemplate.class);
        Channel channel = mock(Channel.class);
        when(channel.messageCount(OutpatientMessagingConstants.DELAY_QUEUE_APPOINTMENT_TIMEOUT_TICK))
                .thenReturn(channelCount);
        when(template.execute(any()))
                .thenAnswer(invocation ->
                        invocation.getArgument(0, ChannelCallback.class).doInRabbit(channel));
        return new AppointmentTimeoutTickSender(template).hasPendingTick();
    }

    /**
     * 自续期开关参数替身（其余五键沿用缺省值，AppointmentTimeoutTickListenerTest 同款口径）。
     *
     * @param selfRearm 自续期开关
     * @return 门诊域参数
     */
    private static OutpatientProperties selfRearm(boolean selfRearm) {
        return new OutpatientProperties(Duration.ofMinutes(15), 1, 90, 3, 90, selfRearm);
    }
}
