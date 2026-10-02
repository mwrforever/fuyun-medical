package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.nursing.api.TaskOverduePayload;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.entity.NursingTask;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.properties.NursingProperties;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 任务逾期扫描升级服务单测（P2 PR-3 Task 9 tick 三段式段①②）：首逾标记 CAS+事务内发布
 * nursing.task.overdue（载荷 escalationCount=1）、升级档 floor(elapsed/interval) 封顶 2 CAS
 * 递增再发布（escalationCount=2）、空扫描零副作用、已完成任务不命中、CAS 竞态零行不发布。
 * MP 3.5.17 单测范式：LambdaQueryWrapper 触达实体 @BeforeAll 手工注册表信息。
 */
@ExtendWith(MockitoExtension.class)
class TaskOverdueServiceImplTest {

    /** 任务行 id（CAS 定位键） */
    private static final long ROW_ID = 701L;

    /** 第二行 id（封顶档用例载体） */
    private static final long ROW_ID_B = 702L;

    /** 任务号 */
    private static final String TASK_NO = "TK2026100200001";

    /** 第二任务号 */
    private static final String TASK_NO_B = "TK2026100200002";

    @Mock
    private NursingTaskMapper taskMapper;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<NursingDomainEvent> eventCaptor;

    private TaskOverdueServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：LambdaQueryWrapper 触达的实体须手工注册表信息（段①②两路扫描查询）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingTask.class);
    }

    @BeforeEach
    void setUp() {
        service = new TaskOverdueServiceImpl(
                taskMapper,
                events,
                new NursingProperties(30, new NursingProperties.TaskOverdue(30, 60, 30, true)),
                txTemplate());
        // tick 消费链路操作者恒为 SYSTEM（监听器 GC15 桥接落位——本服务直驱测试同款模拟）
        OperatorContextHolder.set("SYSTEM");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("段①首逾标记：越阈值在途未标记行 casMarkOverdue 命中 → 事务内发布逾期事件（escalationCount=1）")
    void firstOverdueMarkingPublishesEventWithTierOne() {
        OffsetDateTime planTime = OffsetDateTime.now().minusMinutes(45);
        NursingTask row = candidate(TASK_NO, ROW_ID, TaskStatus.PENDING, false, 0, planTime);
        when(taskMapper.selectList(any())).thenReturn(List.of(row), List.of());
        when(taskMapper.casMarkOverdue(ROW_ID)).thenReturn(1);

        int actions = service.scanAndEscalate();

        assertThat(actions).isEqualTo(1);
        // 逾期事件（id 61）：事务内发布 AFTER_COMMIT 出站；载荷 taskNo/planTime/escalationCount=1（责任护士档）
        verify(events, org.mockito.Mockito.times(1)).publishEvent(eventCaptor.capture());
        NursingDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(NursingMessagingConstants.EVENT_TASK_OVERDUE);
        TaskOverduePayload payload = (TaskOverduePayload) event.payload();
        assertThat(payload.taskNo()).isEqualTo(TASK_NO);
        assertThat(payload.planTime()).isEqualTo(planTime.toInstant());
        assertThat(payload.escalationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("段②升级档：elapsed=90 分钟目标档 min(floor(90/30),2)=2 → CAS 递增再发布（escalationCount=2 护士长档）")
    void escalationTierIncrementsAndPublishesTierTwo() {
        NursingTask row = candidate(
                TASK_NO,
                ROW_ID,
                TaskStatus.IN_PROGRESS,
                true,
                1,
                OffsetDateTime.now().minusMinutes(90));
        when(taskMapper.selectList(any())).thenReturn(List.of(), List.of(row));
        when(taskMapper.casEscalateOverdue(ROW_ID, 1, "SYSTEM")).thenReturn(1);

        int actions = service.scanAndEscalate();

        assertThat(actions).isEqualTo(1);
        verify(taskMapper).casEscalateOverdue(ROW_ID, 1, "SYSTEM");
        // 升级链事件：载荷 escalationCount=2（护士长档——递增后的值）
        verify(events, org.mockito.Mockito.times(1)).publishEvent(eventCaptor.capture());
        TaskOverduePayload payload = (TaskOverduePayload) eventCaptor.getValue().payload();
        assertThat(payload.taskNo()).isEqualTo(TASK_NO);
        assertThat(payload.escalationCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("段②封顶：escalationCount 已达 2（护士长档封顶）不再递增；CAS 期望值不符（并发先递增）零行不发布")
    void escalationCapAndCasRaceZeroRowSkipPublish() {
        // 行一：count=2 已封顶——目标档 2 不大于当前档，CAS 不触达
        NursingTask capped = candidate(
                TASK_NO,
                ROW_ID,
                TaskStatus.PENDING,
                true,
                2,
                OffsetDateTime.now().minusMinutes(120));
        // 行二：count=1 且目标档 2——CAS 期望值 1 竞态零行（他实例先递增），不发布事件
        NursingTask racing = candidate(
                TASK_NO_B,
                ROW_ID_B,
                TaskStatus.PENDING,
                true,
                1,
                OffsetDateTime.now().minusMinutes(95));
        // 行三：段②终态防御替身——扫描快照后已完成行跳过递增与事件（已完成任务不命中）
        NursingTask completed = candidate(
                "TK2026100200003",
                703L,
                TaskStatus.COMPLETED,
                true,
                1,
                OffsetDateTime.now().minusMinutes(95));
        when(taskMapper.selectList(any())).thenReturn(List.of(), List.of(capped, racing, completed));
        when(taskMapper.casEscalateOverdue(ROW_ID_B, 1, "SYSTEM")).thenReturn(0);

        int actions = service.scanAndEscalate();

        assertThat(actions).isZero();
        // 封顶行零 CAS 触达；竞态行 CAS 触达但零行（并发双计防线）——两路均零事件发布
        verify(taskMapper, never()).casEscalateOverdue(eq(ROW_ID), anyInt(), any());
        verify(taskMapper).casEscalateOverdue(ROW_ID_B, 1, "SYSTEM");
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("段③空扫描：两路候选零命中零副作用（无 CAS、无事件、无动作）返回 0")
    void emptyScanHasZeroSideEffects() {
        when(taskMapper.selectList(any())).thenReturn(List.of(), List.of());

        assertThat(service.scanAndEscalate()).isZero();

        verify(taskMapper, never()).casMarkOverdue(anyLong());
        verify(taskMapper, never()).casEscalateOverdue(anyLong(), anyInt(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("段④终态防御：已完成任务不命中（扫描后快照终态行跳过 CAS 与事件发布）")
    void completedTaskIsNotMarked() {
        // 候选集混入 COMPLETED 行（终态防御替身——SQL 谓词外的最后一道内存守卫）
        NursingTask completed = candidate(
                TASK_NO,
                ROW_ID,
                TaskStatus.COMPLETED,
                false,
                0,
                OffsetDateTime.now().minusMinutes(45));
        when(taskMapper.selectList(any())).thenReturn(List.of(completed), List.of());

        assertThat(service.scanAndEscalate()).isZero();

        verify(taskMapper, never()).casMarkOverdue(anyLong());
        verifyNoInteractions(events);
    }

    /**
     * 候选行替身。
     *
     * @param taskNo  任务号
     * @param id      行 id
     * @param status  任务状态
     * @param flagged 逾期标记
     * @param count   升级次数
     * @param planTime 计划时间
     * @return 任务行替身，非空
     */
    private NursingTask candidate(
            String taskNo, long id, TaskStatus status, boolean flagged, int count, OffsetDateTime planTime) {
        NursingTask row = new NursingTask();
        row.setId(id);
        row.setTaskNo(taskNo);
        row.setWardId("W01");
        row.setStatus(status.getCode());
        row.setOverdueFlag(flagged);
        row.setEscalationCount(count);
        row.setPlanTime(planTime);
        return row;
    }

    /**
     * 无资源事务模板（ExecutionConfirmCompensatorTest 同款形态：段①②各自动作的事务边界承载，
     * 无真实资源提交）。
     *
     * @return 编程式事务模板，非空
     */
    private TransactionTemplate txTemplate() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {

            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(
                    Object transaction, org.springframework.transaction.TransactionDefinition definition) {
                // 无资源事务令牌：同步激活由父类统一完成
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                // 无资源 commit
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                // 无资源 rollback
            }
        });
    }
}
