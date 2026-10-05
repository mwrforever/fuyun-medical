package com.fuyun.outpatient.internal;

import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.properties.OutpatientProperties;
import com.fuyun.outpatient.service.IAppointmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 号源超时 tick 消费侧（P2 PR-4E Task 8，W-27 tick 双通道兜底，nursing TaskOverdueTickListener
 * 克隆基准）：消费 q.outpatient.appointment-timeout.tick（delay.appointment-timeout-tick 60 秒档位
 * 到期经 DLX 回投 fy.topic 本路由键）——委托 {@link IAppointmentService#scanAndReleaseTimedOut()}
 * 惰性扫描过期占位单（RESERVED 且 pay_deadline 已过）逐单走 markTimeout 释放面（15m 延迟消息
 * 通道丢帧/档位积压时的兜底收敛面——双通道安全由 RESERVED→NO_SHOW CAS 0 行幂等跳过保证），
 * 扫描后自续期发布下一条 tick 心跳帧（tickSelfRearm 开关断电面；空扫描零命中也续期——进程存活
 * 即有心跳，本监听器自身无 @Transactional——消费事务外红线）。
 *
 * <p>tick 帧为空载荷 ping 非事件信封——不经 IdempotentConsumerSupport 三段式（无 eventId
 * 可去重；幂等由扫描侧 markTimeout CAS 谓词与空 tick 零副作用承载，A.5-7 延迟档位治理口径）；
 * tick 键无 event_registry 登记面，消费队列由 OutpatientMessagingConfig 自声明绑定（先登记后订阅
 * 红线豁免——QueueGovernorImpl declareConsumerQueue 会因无登记行抛 ISE，自声明姿态逐字镜像其
 * 队列形态，豁免注记见 OutpatientMessagingConstants 类注释）。
 *
 * <p>GC15 operator 桥：MQ 消费链路无登录上下文——入口显式 OperatorContextHolder.set
 * ("SYSTEM") + try/finally clear。
 *
 * <p>归 internal/：容器驱动入口禁外引；Bean 注册点 OutpatientMessagingConfig @Import。
 */
@Slf4j
public class AppointmentTimeoutTickListener {

    /** 系统触发面操作者（GC15 桥接口径——审计列落位与 finally 清理配对） */
    private static final String SYSTEM_OPERATOR_TEXT = "SYSTEM";

    private final IAppointmentService appointmentService;

    private final AppointmentTimeoutTickSender tickSender;

    private final OutpatientProperties properties;

    /**
     * 全参构造器（装配归 OutpatientMessagingConfig @Import）。
     *
     * @param appointmentService 预约服务，非空；scanAndReleaseTimedOut 为 tick 扫描委托面
     * @param tickSender         tick 心跳帧发送器（自续期发布面），非空
     * @param properties         门诊域参数，非空；tickSelfRearm 为自续期开关
     */
    public AppointmentTimeoutTickListener(
            IAppointmentService appointmentService,
            AppointmentTimeoutTickSender tickSender,
            OutpatientProperties properties) {
        this.appointmentService = appointmentService;
        this.tickSender = tickSender;
        this.properties = properties;
    }

    /**
     * tick 消费入口（q.outpatient.appointment-timeout.tick，事务外——无 @Transactional 红线）：
     * 委托惰性扫描（逐单独立事务承载）→ 自续期发布（扫描各事务提交后）。异常上抛由容器有界
     * 重试承载（重投同一 tick 帧——已处理行经 CAS 幂等收敛）。
     *
     * @param message 原始消息帧（空载荷 ping），非空
     */
    @RabbitListener(queues = OutpatientMessagingConstants.QUEUE_APPOINTMENT_TIMEOUT_TICK)
    public void onAppointmentTimeoutTick(Message message) {
        // GC15 operator 桥：tick 链路系统操作者落位（finally 清理防线程复用残留）
        OperatorContextHolder.set(SYSTEM_OPERATOR_TEXT);
        try {
            int processed = appointmentService.scanAndReleaseTimedOut();
            rearmAfterCommit(processed);
        } finally {
            OperatorContextHolder.clear();
        }
    }

    /**
     * 自续期发布（事务提交后语义：本监听器无外层事务，扫描逐单独立事务在调用返回时均已提交——
     * 此处发布即事务提交后）：tickSelfRearm 开关断电面（false=链路静止，仅显式重启播种可再激活）。
     *
     * @param processed 本轮处理行数（日志观测口径）
     */
    private void rearmAfterCommit(int processed) {
        if (!properties.tickSelfRearm()) {
            log.info("号源超时 tick 自续期已关闭（tickSelfRearm=false），本轮心跳终止：processed={}", processed);
            return;
        }
        // 心跳语义：空扫描零命中也续期（进程存活即有心跳）
        tickSender.sendTick();
        log.info("号源超时 tick 完成：超时处理={}，下一条心跳已续期", processed);
    }
}
