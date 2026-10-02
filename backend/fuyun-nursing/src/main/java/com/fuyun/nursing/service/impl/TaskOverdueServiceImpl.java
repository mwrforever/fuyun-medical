package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.nursing.api.TaskOverduePayload;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.entity.NursingTask;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.internal.NurseBoardPushEvent;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.properties.NursingProperties;
import com.fuyun.nursing.service.ITaskOverdueService;
import com.fuyun.nursing.vo.NurseBoardPushFrame;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 任务逾期扫描升级服务实现（P2 PR-3 Task 9，M05 Spec :127 动作式逾期 + §12-3）：
 * tick 三段式段①②——扫表判定在事务外（有界两路查询，避免长事务扫表），动作批次经
 * TransactionTemplate 独立事务承载（置位/递增与 nursing.task.overdue 事件事务内发布，
 * AFTER_COMMIT 出站 GC8）。升级链「责任护士→护士长」动作式：escalationCount 1=责任护士档
 * （首逾标记即达）、2=护士长档（封顶）；并发双计防线=casEscalateOverdue 期望值 CAS
 * （多实例同刻扫描仅一方命中）。WS board 推送面（Task 11 已接线）：段①② CAS 命中行事务内
 * 发布 {@link NurseBoardPushEvent}（type=TASK_OVERDUE，载荷含 taskNo/wardId 维度——派发上下文
 * §1.2 口径），NurseBoardPushListener 于独立事务提交后（AFTER_COMMIT+fallback）推送
 * /topic/nursing/board/{wardId}；warn 级触达日志保留为伴随日志（推送为主面）。
 * 业务时间服务器时间（GC25）。线程安全：无状态 singleton。
 */
@Slf4j
public class TaskOverdueServiceImpl implements ITaskOverdueService {

    /** 系统链路等无登录上下文场景的操作者回退值（与 V805 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 首逾标记后的升级档位（overdue_flag 单向置位无复位——flag=false 行 count 恒 0，置位后即 1=责任护士档） */
    private static final int FIRST_OVERDUE_TIER = 1;

    /** 升级档封顶（2=护士长档；升级链「责任护士→护士长」止步于两级——M01 通知模块缺位注记） */
    private static final int ESCALATION_TIER_CAP = 2;

    /** 单轮扫描行数上界（两路各限——有界扫描纪律，ExecutionConfirmCompensator 同族；越界行由后续 tick 轮转收敛） */
    private static final int SCAN_LIMIT = 500;

    private final NursingTaskMapper taskMapper;

    private final ApplicationEventPublisher events;

    private final NursingProperties properties;

    private final TransactionTemplate transactionTemplate;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param taskMapper         护理任务 mapper，非空；两路候选扫描与 CAS 置位/递增面
     * @param events             Spring 应用事件发布器，非空；逾期事件事务内发布经
     *                           NursingEventPublisher AFTER_COMMIT 出 MQ（GC8 红线）
     * @param properties         护理域参数，非空；taskOverdue 组为 tick 判定基准
     * @param transactionTemplate 编程式事务模板，非空；动作批次独立事务承载（扫表在事务外）
     */
    public TaskOverdueServiceImpl(
            NursingTaskMapper taskMapper,
            ApplicationEventPublisher events,
            NursingProperties properties,
            TransactionTemplate transactionTemplate) {
        this.taskMapper = taskMapper;
        this.events = events;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 逾期扫描与升级动作（扫表在事务外，两路动作各自独立事务）：见接口注。
     *
     * @return 本轮动作总数（首逾标记数 + 升级递增数；零=空扫描零副作用）
     */
    @Override
    public int scanAndEscalate() {
        OffsetDateTime now = OffsetDateTime.now();
        NursingProperties.TaskOverdue config = properties.taskOverdue();
        // 数据库读操作：段①候选扫描（在途未标记 + 越首逾阈值，计划时间升序有界——事务外扫表）
        List<NursingTask> firstMarkCandidates = taskMapper.selectList(new LambdaQueryWrapper<NursingTask>()
                .in(NursingTask::getStatus, TaskStatus.PENDING.getCode(), TaskStatus.IN_PROGRESS.getCode())
                .eq(NursingTask::getOverdueFlag, false)
                .lt(NursingTask::getPlanTime, now.minusMinutes(config.remindAfterMinutes()))
                .orderByAsc(NursingTask::getPlanTime)
                .last("LIMIT " + SCAN_LIMIT));
        // 数据库读操作：段②候选扫描（在途已标记 + 越升级链阈值 + 未达封顶档——目标档位内存计算，SQL 侧收敛可行集）
        List<NursingTask> escalateCandidates = taskMapper.selectList(new LambdaQueryWrapper<NursingTask>()
                .in(NursingTask::getStatus, TaskStatus.PENDING.getCode(), TaskStatus.IN_PROGRESS.getCode())
                .eq(NursingTask::getOverdueFlag, true)
                .lt(NursingTask::getEscalationCount, ESCALATION_TIER_CAP)
                .lt(NursingTask::getPlanTime, now.minusMinutes(config.escalateAfterMinutes()))
                .orderByAsc(NursingTask::getPlanTime)
                .last("LIMIT " + SCAN_LIMIT));
        if (firstMarkCandidates.isEmpty() && escalateCandidates.isEmpty()) {
            // 空 tick 幂等忽略：零命中零副作用（自续期心跳归监听器承载——进程存活即有心跳）
            log.debug("任务逾期扫描零命中（空 tick 幂等忽略）");
            return 0;
        }
        int marked = markFirstOverdue(firstMarkCandidates);
        int escalated = escalateOverdueTiers(escalateCandidates, now);
        // WS 触达伴随日志（推送为主面——段①② 内逐行发布 TASK_OVERDUE 帧，Task 11 接线实况）
        if (marked > 0) {
            log.warn("任务逾期首标触达（大屏 TASK_OVERDUE 帧已随独立事务发布）：首标={}/{}", marked, firstMarkCandidates.size());
        }
        if (escalated > 0) {
            log.warn(
                    "任务逾期升级链触达（责任护士→护士长；通知承载降级=WS 推送+任务列表可见，M01 通知模块缺位注记）：递增={}/{}",
                    escalated,
                    escalateCandidates.size());
        }
        return marked + escalated;
    }

    /**
     * 段①首逾标记（独立事务承载）：逐行终态防御 + casMarkOverdue 置位（CAS 命中才发布）——
     * 置位与事件发布同事务成败与共（GC8：事件事务内发布 AFTER_COMMIT 出站）。
     *
     * @param candidates 首逾候选行（在途未标记越阈值），非空
     * @return 首逾标记命中行数（CAS 零行=并发他方已标记，幂等跳过）
     */
    private int markFirstOverdue(List<NursingTask> candidates) {
        if (candidates.isEmpty()) {
            return 0;
        }
        String operator = operator();
        return transactionTemplate.execute(status -> {
            int marked = 0;
            for (NursingTask row : candidates) {
                // 终态防御：扫描快照后任务已完成/取消——置位与事件均跳过（已完成任务不命中）
                if (!isInFlight(row)) {
                    continue;
                }
                // 数据库写操作：首逾标记 CAS（overdue_flag=false 谓词仅首次递增——count 0→1 责任护士档）
                if (taskMapper.casMarkOverdue(row.getId()) == 1) {
                    events.publishEvent(new NursingDomainEvent(
                            NursingMessagingConstants.EVENT_TASK_OVERDUE,
                            new TaskOverduePayload(
                                    row.getTaskNo(), row.getPlanTime().toInstant(), FIRST_OVERDUE_TIER)));
                    // 消息发送：大屏任务逾期帧（taskNo/wardId 维度，事务内发布提交后出站——Task 11 接线）
                    publishOverdueBoardEvent(row, FIRST_OVERDUE_TIER);
                    marked++;
                }
            }
            return marked;
        });
    }

    /**
     * 段②升级档递增（独立事务承载）：目标档 = floor(elapsed/escalateInterval) 封顶 2，未达目标档
     * 的行经 casEscalateOverdue 期望值 CAS 单步递增（每 tick 至多一步——落后多档时经后续 tick
     * 轮转收敛）再发布同事件（载荷携递增后档位）。
     *
     * @param candidates 升级候选行（在途已标记越升级阈值未封顶），非空
     * @param now        扫描基准时刻（elapsed 计算锚），非空
     * @return 升级递增命中行数（CAS 零行=并发先递增，幂等跳过）
     */
    private int escalateOverdueTiers(List<NursingTask> candidates, OffsetDateTime now) {
        if (candidates.isEmpty()) {
            return 0;
        }
        NursingProperties.TaskOverdue config = properties.taskOverdue();
        String operator = operator();
        return transactionTemplate.execute(status -> {
            int escalated = 0;
            for (NursingTask row : candidates) {
                // 终态防御：扫描快照后任务已完成/取消——递增与事件均跳过
                if (!isInFlight(row)) {
                    continue;
                }
                long elapsedMinutes = Duration.between(row.getPlanTime(), now).toMinutes();
                // 目标档 = floor(elapsed/步进) 封顶 2（步进零值守卫防配置误置除零——Math.max 收敛为 1 分钟）
                int targetTier = (int)
                        Math.min(elapsedMinutes / Math.max(1, config.escalateIntervalMinutes()), ESCALATION_TIER_CAP);
                // 已达目标档（含封顶 2 护士长档）不再递增——升级链止步
                if (row.getEscalationCount() >= targetTier) {
                    continue;
                }
                // 数据库写操作：升级档 CAS 递增（期望值比对防并发双计——多实例同刻仅一方命中）
                if (taskMapper.casEscalateOverdue(row.getId(), row.getEscalationCount(), operator) == 1) {
                    events.publishEvent(new NursingDomainEvent(
                            NursingMessagingConstants.EVENT_TASK_OVERDUE,
                            new TaskOverduePayload(
                                    row.getTaskNo(), row.getPlanTime().toInstant(), row.getEscalationCount() + 1)));
                    // 消息发送：大屏任务逾期帧（递增后档位承载，事务内发布提交后出站——Task 11 接线）
                    publishOverdueBoardEvent(row, row.getEscalationCount() + 1);
                    escalated++;
                }
            }
            return escalated;
        });
    }

    /**
     * 大屏任务逾期帧发布（段①②共用单点）：type=TASK_OVERDUE，载荷含 taskNo/taskType/planTime/
     * escalationCount/wardId——派发上下文 §1.2「载荷含 taskNo/wardId 维度」口径；wardId 缺失
     * （数据异常防御）跳过发布留痕，不阻断动作批次。
     *
     * @param row             任务行（CAS 命中快照），非空
     * @param escalationCount 出网档位（首标=1；递增路=递增后值）
     */
    private void publishOverdueBoardEvent(NursingTask row, int escalationCount) {
        if (row.getWardId() == null || row.getWardId().isBlank()) {
            log.warn("任务逾期大屏帧跳过（任务行无病区归属，数据异常留痕）：taskNo={}", row.getTaskNo());
            return;
        }
        events.publishEvent(new NurseBoardPushEvent(
                row.getWardId(),
                NurseBoardPushFrame.TYPE_TASK_OVERDUE,
                new NurseBoardPushFrame.OverdueTaskPayload(
                        row.getTaskNo(), row.getTaskType(), row.getPlanTime(), escalationCount, row.getWardId()),
                Instant.now()));
    }

    /**
     * 在途态判定（终态防御共用）：PENDING/IN_PROGRESS 之外（COMPLETED/CANCELLED）不参与
     * 逾期动作——已完成任务不命中（扫描谓词外最后一道内存守卫，快照后状态漂移防线）。
     *
     * @param row 任务行（扫描快照），非空
     * @return true=在途态（可执行逾期动作）
     */
    private static boolean isInFlight(NursingTask row) {
        return TaskStatus.PENDING.getCode().equals(row.getStatus())
                || TaskStatus.IN_PROGRESS.getCode().equals(row.getStatus());
    }

    /** 操作者取值（免登录上下文回退 system，与审计列默认同源——tick 链路监听器已桥接 SYSTEM）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
