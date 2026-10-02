package com.fuyun.nursing.internal;

import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.properties.NursingProperties;
import com.fuyun.nursing.service.ITaskOverdueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 任务逾期 tick 消费侧（P2 PR-3 Task 9，M05 FU-M05-07 tick 三段式编排）：消费
 * q.nursing.task-overdue.tick（delay.task-overdue 60 秒档位到期经 DLX 回投 fy.topic 本路由键）
 * ——①②扫表升级动作委托 {@link ITaskOverdueService}（扫表在事务外、动作批次独立事务承载，
 * 事件 AFTER_COMMIT 出站）；Task 5 遗留接线：同流程挂接 {@link ExecutionConfirmCompensator}
 * 补偿扫描（COMPENSATING 行重试回签，事务边界照其 javadoc——单行独立事务）；③自续期：
 * 扫描与补偿各事务提交后发布下一条 tick 心跳帧（空扫描零命中也续期——进程存活即有心跳，
 * brief 冻结语义；本监听器自身无 @Transactional——消费事务外红线）。
 *
 * <p>tick 帧为空载荷 ping 非事件信封——不经 IdempotentConsumerSupport 三段式（无 eventId
 * 可去重；幂等由扫描侧 CAS 谓词与空 tick 零副作用承载，A.5-7 延迟档位治理口径）；tick 键
 * 无 event_registry 登记面，消费队列由 NursingMessagingConfig 自声明绑定（先登记后订阅红线
 * 豁免——QueueGovernorImpl declareConsumerQueue 会因无登记行抛 ISE，自声明姿态逐字镜像其
 * 队列形态，豁免注记见 NursingMessagingConstants 类注释）。
 *
 * <p>GC15 operator 桥：MQ 消费链路无登录上下文——入口显式 OperatorContextHolder.set
 * ("SYSTEM") + try/finally clear（含 Compensator 挂接调用段；Compensator 内部自成 SYSTEM
 * 桥，嵌套落位无害）。
 *
 * <p>归 internal/：容器驱动入口禁外引；Bean 注册点 NursingMessagingConfig @Import。
 */
@Slf4j
public class TaskOverdueTickListener {

    /** 系统触发面操作者（GC15 桥接口径——审计列落位与 finally 清理配对） */
    private static final String SYSTEM_OPERATOR_TEXT = "SYSTEM";

    private final ITaskOverdueService taskOverdueService;

    private final ExecutionConfirmCompensator compensator;

    private final TaskOverdueTickSender tickSender;

    private final NursingProperties properties;

    /**
     * 全参构造器（装配归 NursingMessagingConfig @Import）。
     *
     * @param taskOverdueService 逾期扫描升级服务（tick 段①②动作体），非空
     * @param compensator        执行回签补偿扫描组件（Task 5 遗留接线——tick 驱动补偿面），非空
     * @param tickSender         tick 心跳帧发送器（段③自续期发布面），非空
     * @param properties         护理域参数，非空；taskOverdue.tickSelfRearm 为自续期开关
     */
    public TaskOverdueTickListener(
            ITaskOverdueService taskOverdueService,
            ExecutionConfirmCompensator compensator,
            TaskOverdueTickSender tickSender,
            NursingProperties properties) {
        this.taskOverdueService = taskOverdueService;
        this.compensator = compensator;
        this.tickSender = tickSender;
        this.properties = properties;
    }

    /**
     * tick 消费入口（q.nursing.task-overdue.tick，事务外——无 @Transactional 红线）：
     * 扫描升级（段①②）→ 回签补偿扫描（Task 5 接线）→ 自续期发布（段③——两路事务提交后）。
     * 异常上抛由容器有界重试承载（重投同一 tick 帧——幂等收敛）。
     *
     * @param message 原始消息帧（空载荷 ping），非空
     */
    @RabbitListener(queues = NursingMessagingConstants.QUEUE_TASK_OVERDUE_TICK)
    public void onTaskOverdueTick(Message message) {
        // GC15 operator 桥：tick 链路系统操作者落位（finally 清理防线程复用残留）
        OperatorContextHolder.set(SYSTEM_OPERATOR_TEXT);
        try {
            int actions = taskOverdueService.scanAndEscalate();
            int confirmed = compensator.compensate();
            rearmAfterCommit(actions, confirmed);
        } finally {
            OperatorContextHolder.clear();
        }
    }

    /**
     * 段③自续期发布（事务提交后语义：本监听器无外层事务，扫描与补偿的独立事务在调用返回时
     * 均已提交——此处发布即事务提交后）：tickSelfRearm 开关断电面（false=链路静止，仅显式
     * 重启播种可再激活）。
     *
     * @param actions   本轮逾期动作数（日志观测口径）
     * @param confirmed 本轮回签补偿置位数（日志观测口径）
     */
    private void rearmAfterCommit(int actions, int confirmed) {
        if (!properties.taskOverdue().tickSelfRearm()) {
            log.info("任务逾期 tick 自续期已关闭（tickSelfRearm=false），本轮心跳终止：actions={}，confirmed={}", actions, confirmed);
            return;
        }
        // 心跳语义：空扫描零命中也续期（进程存活即有心跳——brief 冻结语义）
        tickSender.sendTick();
        log.info("任务逾期 tick 完成：逾期动作={}，回签补偿置位={}，下一条心跳已续期", actions, confirmed);
    }
}
