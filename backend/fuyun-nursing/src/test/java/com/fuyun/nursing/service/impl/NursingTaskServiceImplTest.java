package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.api.TaskCompletedPayload;
import com.fuyun.nursing.api.TaskCreatedPayload;
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.dto.NursingTaskCancelRequest;
import com.fuyun.nursing.dto.NursingTaskCreateRequest;
import com.fuyun.nursing.entity.NursingTask;
import com.fuyun.nursing.enums.TaskPriority;
import com.fuyun.nursing.enums.TaskSource;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.enums.TaskType;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.properties.NursingProperties;
import com.fuyun.nursing.vo.NursingTaskVO;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.apache.ibatis.annotations.Update;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 护理任务域服务单测（Task 7 十用例冻结集 + 补充覆盖锚）：创建发号默认 PENDING、创建事件载荷
 * 逐字断言、补录历史任务拒收（NS-1016，本计划自增校验）、完成 CAS 流转与盖章、取消原因强制
 * 留痕、读时惰性逾期单次递增、逾期事件 P1 零发布锚、清单三键过滤与升序、在途任务谓词钉死、
 * 巡视打卡直落终态。MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息；
 * 条件更新断言直读 @Update 注解 SQL（GC26 可执行锚）。
 */
@ExtendWith(MockitoExtension.class)
class NursingTaskServiceImplTest {

    /** I 型 14 位合法 visit_id（任务所属就诊） */
    private static final String VISIT = "I2026092200001";

    /** 病区编码（任务清单检索键） */
    private static final String WARD = "W01";

    /** 任务号（发号器桩固定返回值） */
    private static final String TASK_NO = "TK2026092200001";

    /** 首条插入行固定 id（insert 桩回填值；惰性逾期 CAS 用例载体） */
    private static final long ROW_ID = 601L;

    @Mock
    private NursingTaskMapper taskMapper;

    @Mock
    private NursingSeqGate seqGate;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<NursingTask> rowCaptor;

    @Captor
    private ArgumentCaptor<NursingDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<NursingTask>> queryCaptor;

    private NursingTaskServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（任务三读面 + 惰性逾期）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingTask.class);
    }

    @BeforeEach
    void setUp() {
        // 阈值固定 30 分钟（NursingProperties 默认值，用例 6 惰性逾期判定基准）
        service = new NursingTaskServiceImpl(taskMapper, seqGate, events, new NursingProperties(30));
        ReflectionTestUtils.setField(service, "baseMapper", taskMapper);
        OperatorContextHolder.set("nurse-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("任务创建：发号器取 TK 号、默认 PENDING 态（overdueFlag=false、escalationCount=0）")
    void createGeneratesTaskNoAndDefaultsPending() {
        when(seqGate.nextNo("TK")).thenReturn(TASK_NO);
        when(taskMapper.insert(any(NursingTask.class))).thenAnswer(insertWithId(ROW_ID));

        NursingTaskVO vo = service.create(request(null, null, null));

        assertThat(vo.taskNo()).isEqualTo(TASK_NO);
        assertThat(vo.status()).isEqualTo(TaskStatus.PENDING.getCode());
        assertThat(vo.overdueFlag()).isFalse();
        assertThat(vo.escalationCount()).isZero();
        verify(taskMapper).insert(rowCaptor.capture());
        NursingTask row = rowCaptor.getValue();
        // 落库默认面：状态 PENDING + 逾期动作列零值 + 优先级缺省 NORMAL（与 V805 列默认同源）
        assertThat(row.getStatus()).isEqualTo(TaskStatus.PENDING.getCode());
        assertThat(row.getOverdueFlag()).isFalse();
        assertThat(row.getEscalationCount()).isZero();
        assertThat(row.getPriority()).isEqualTo(TaskPriority.NORMAL.getCode());
    }

    @Test
    @DisplayName("任务创建事件：nursing.task.created 载荷 taskNo/taskType/source 逐字断言（GC8 事务内发布）")
    void createPublishesTaskCreatedEvent() {
        when(seqGate.nextNo("TK")).thenReturn(TASK_NO);
        when(taskMapper.insert(any(NursingTask.class))).thenAnswer(insertWithId(ROW_ID));

        service.create(request(TaskType.MEDICATION.getCode(), TaskSource.MANUAL.getCode(), null));

        verify(events).publishEvent(eventCaptor.capture());
        NursingDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(NursingMessagingConstants.EVENT_TASK_CREATED);
        TaskCreatedPayload payload = (TaskCreatedPayload) event.payload();
        assertThat(payload.taskNo()).isEqualTo(TASK_NO);
        assertThat(payload.patientId()).isEqualTo(7L);
        assertThat(payload.visitId()).isEqualTo(VISIT);
        assertThat(payload.taskType()).isEqualTo(TaskType.MEDICATION.getCode());
        assertThat(payload.source()).isEqualTo(TaskSource.MANUAL.getCode());
    }

    @Test
    @DisplayName("任务创建补录红线：planTime 早于当前 24 小时以上拒 NS-1016，不发号不落库不发事件")
    void createRejectsPlanTimeInPastBeyondTolerance() {
        NursingTaskCreateRequest history = new NursingTaskCreateRequest(
                7L,
                VISIT,
                WARD,
                "01",
                TaskType.TURN.getCode(),
                null,
                null,
                OffsetDateTime.now().minusHours(25),
                null,
                null);

        assertThatThrownBy(() -> service.create(history)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).contains("补录");
        });
        verifyNoInteractions(seqGate, events);
        verify(taskMapper, never()).insert(any(NursingTask.class));
    }

    @Test
    @DisplayName("任务完成：CAS 1 行置 COMPLETED 并盖章 completedAt、发布完成事件；重复完成拒 NS-1011")
    void completeTransitionsAndStampsCompletedAt() {
        when(taskMapper.casComplete(TASK_NO, "nurse-01")).thenReturn(1, 0);
        when(taskMapper.selectOne(any())).thenReturn(completedRow());

        NursingTaskVO vo = service.complete(TASK_NO);

        assertThat(vo.status()).isEqualTo(TaskStatus.COMPLETED.getCode());
        assertThat(vo.completedAt()).isNotNull();
        // 完成事件：载荷 taskNo + 终态 COMPLETED（TaskCompletedPayload 契约）
        verify(events, times(1)).publishEvent(eventCaptor.capture());
        NursingDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(NursingMessagingConstants.EVENT_TASK_COMPLETED);
        TaskCompletedPayload payload = (TaskCompletedPayload) event.payload();
        assertThat(payload.taskNo()).isEqualTo(TASK_NO);
        assertThat(payload.status()).isEqualTo(TaskStatus.COMPLETED.getCode());

        // 重复完成：CAS 0 行（已终态）→ NS-1011，事件不重复发布
        assertThatThrownBy(() -> service.complete(TASK_NO)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.TASK_STATE_NOT_ALLOWED);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1011");
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(events, times(1)).publishEvent(any(NursingDomainEvent.class));
        // GC26 可执行锚：完成必须为 @Update 注解 SQL 条件更新（在途两态 + deleted=0）
        String sql = recordSql("casComplete", String.class, String.class);
        assertThat(sql)
                .contains("status = 'COMPLETED'")
                .contains("completed_at = now()")
                .contains("WHERE task_no = #{taskNo}")
                .contains("status IN ('PENDING', 'IN_PROGRESS')")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("任务取消：空原因拒 NS-1019；非空原因 CAS 置 CANCELLED 且原因落库、发布完成事件（status=CANCELLED）")
    void cancelRequiresReasonAndSetsStatus() {
        // 空原因：服务面强制留痕校验（NS-1019），零副作用
        assertThatThrownBy(() -> service.cancel(TASK_NO, new NursingTaskCancelRequest("")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(events);
        verify(taskMapper, never()).casCancel(any(), any(), any());

        // 非空原因：CAS 命中 → CANCELLED + 原因落库 + 终态事件（cancel 同发 task.completed）
        when(taskMapper.casCancel(TASK_NO, "患者病情好转", "nurse-01")).thenReturn(1);
        when(taskMapper.selectOne(any())).thenReturn(cancelledRow());

        NursingTaskVO vo = service.cancel(TASK_NO, new NursingTaskCancelRequest("患者病情好转"));

        assertThat(vo.status()).isEqualTo(TaskStatus.CANCELLED.getCode());
        assertThat(vo.cancelReason()).isEqualTo("患者病情好转");
        verify(events, times(1)).publishEvent(eventCaptor.capture());
        TaskCompletedPayload payload =
                (TaskCompletedPayload) eventCaptor.getValue().payload();
        assertThat(payload.taskNo()).isEqualTo(TASK_NO);
        assertThat(payload.status()).isEqualTo(TaskStatus.CANCELLED.getCode());
        // GC26 可执行锚：取消必须为 @Update 注解 SQL 条件更新（在途两态 + 原因留痕 + deleted=0）
        String sql = recordSql("casCancel", String.class, String.class, String.class);
        assertThat(sql)
                .contains("status = 'CANCELLED'")
                .contains("cancel_reason = #{reason}")
                .contains("WHERE task_no = #{taskNo}")
                .contains("status IN ('PENDING', 'IN_PROGRESS')")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("读时惰性逾期：planTime 超 30 分钟阈值 → casMarkOverdue 单次递增（escalationCount=1），再查不重复递增")
    void listMarksOverdueLazilyOncePerRow() {
        NursingTask firstRow =
                taskRow(TASK_NO, TaskStatus.PENDING, OffsetDateTime.now().minusMinutes(60));
        // 再查替身：真实再查语义——DB 行已被首查 CAS 置位（overdue_flag=true、count=1），非同一内存对象
        NursingTask secondRow =
                taskRow(TASK_NO, TaskStatus.PENDING, OffsetDateTime.now().minusMinutes(60));
        secondRow.setOverdueFlag(true);
        secondRow.setEscalationCount(1);
        when(taskMapper.selectList(any())).thenReturn(List.of(firstRow), List.of(secondRow));
        when(taskMapper.casMarkOverdue(ROW_ID)).thenReturn(1);

        List<NursingTaskVO> first = service.list(WARD, null, null);

        // 首查：CAS 命中 → overdueFlag 置位且 escalationCount 恰递增 1（出参与库态一致）
        assertThat(first).hasSize(1);
        assertThat(first.get(0).overdueFlag()).isTrue();
        assertThat(first.get(0).escalationCount()).isEqualTo(1);
        verify(taskMapper, times(1)).casMarkOverdue(ROW_ID);

        // 再查：行已带 overdue_flag=true（overdue_flag=false 谓词防重复递增），不再触发 CAS，count 仍为 1
        List<NursingTaskVO> second = service.list(WARD, null, null);
        assertThat(second.get(0).overdueFlag()).isTrue();
        assertThat(second.get(0).escalationCount()).isEqualTo(1);
        verify(taskMapper, times(1)).casMarkOverdue(ROW_ID);

        // GC26 可执行锚：逾期标记必须为 @Update 注解 SQL 条件更新（overdue_flag=false 谓词仅首次递增）
        String sql = recordSql("casMarkOverdue", long.class);
        assertThat(sql)
                .contains("overdue_flag = true")
                .contains("escalation_count = escalation_count + 1")
                .contains("WHERE id = #{id}")
                .contains("overdue_flag = false")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("逾期事件 P1 零发布锚：读时惰性逾期判定不发布任何事件（nursing.task.overdue 归 P2 延迟队列）")
    void listDoesNotPublishOverdueEvent() {
        NursingTask row =
                taskRow(TASK_NO, TaskStatus.PENDING, OffsetDateTime.now().minusMinutes(60));
        when(taskMapper.selectList(any())).thenReturn(List.of(row));
        when(taskMapper.casMarkOverdue(ROW_ID)).thenReturn(1);

        service.list(WARD, null, null);

        // P1 占位不发布：读路径零事件（含 EVENT_TASK_OVERDUE，V800 占位登记由 P2 实装）
        verify(events, never()).publishEvent(any(NursingDomainEvent.class));
    }

    @Test
    @DisplayName("任务清单：ward/status/date 三键过滤（当日窗口含头不含尾），返回集按 planTime 升序")
    void listFiltersByWardStatusAndDate() {
        LocalDate day = LocalDate.of(2026, 9, 22);
        NursingTask earlier = taskRow(
                "TK2026092200001",
                TaskStatus.PENDING,
                day.atTime(8, 0).atOffset(OffsetDateTime.now().getOffset()));
        NursingTask later = taskRow(
                "TK2026092200002",
                TaskStatus.PENDING,
                day.atTime(10, 0).atOffset(OffsetDateTime.now().getOffset()));
        when(taskMapper.selectList(any())).thenReturn(List.of(earlier, later));

        List<NursingTaskVO> result = service.list(WARD, TaskStatus.PENDING, day);

        // 升序：mock 按计划时间升序回放，出参保持同序（DB 侧 ORDER BY plan_time 钉死）
        assertThat(result).extracting(NursingTaskVO::taskNo).containsExactly("TK2026092200001", "TK2026092200002");
        verify(taskMapper).selectList(queryCaptor.capture());
        LambdaQueryWrapper<NursingTask> wrapper = rendered(queryCaptor.getValue());
        // 谓词根因锚：病区 + 状态 + 当日窗口（含头不含尾）三键（跨病区/跨状态/跨日行由 DB 谓词滤除）
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(
                        WARD,
                        TaskStatus.PENDING.getCode(),
                        day.atStartOfDay(),
                        day.plusDays(1).atStartOfDay());
        assertThat(wrapper.getSqlSegment()).contains("ORDER BY").contains("plan_time");
    }

    @Test
    @DisplayName("在途任务查询：仅 PENDING/IN_PROGRESS 行（COMPLETED/CANCELLED 由 status IN 谓词滤除），计划时间升序")
    void inFlightByVisitReturnsPendingAndInProgressOnly() {
        NursingTask row =
                taskRow(TASK_NO, TaskStatus.IN_PROGRESS, OffsetDateTime.now().plusMinutes(30));
        when(taskMapper.selectList(any())).thenReturn(List.of(row));

        List<NursingTaskVO> result = service.inFlightByVisit(VISIT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).taskNo()).isEqualTo(TASK_NO);
        verify(taskMapper).selectList(queryCaptor.capture());
        LambdaQueryWrapper<NursingTask> wrapper = rendered(queryCaptor.getValue());
        // 谓词根因锚：visit_id + status IN (PENDING, IN_PROGRESS)（终态行不返回的依据）
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(VISIT, TaskStatus.PENDING.getCode(), TaskStatus.IN_PROGRESS.getCode());
        assertThat(wrapper.getSqlSegment()).contains("IN").contains("ORDER BY").contains("plan_time");
        // 在途行未逾期：零逾期 CAS 触达（惰性判定只在越阈值行上发生）
        verify(taskMapper, never()).casMarkOverdue(anyLong());
    }

    @Test
    @DisplayName("巡视打卡：建 PATROL 行直落 COMPLETED（source=MANUAL、assignedNurse=当前操作者、planTime=打卡时刻）")
    void patrolCreatesCompletedPatrolTask() {
        // 打卡基准时刻（服务调用前取值：planTime/completedAt 须落在基准时刻之后的 2 秒容差窗内）
        OffsetDateTime patrolledAt = OffsetDateTime.now();
        when(seqGate.nextNo("TK")).thenReturn("TK2026092200002");
        when(taskMapper.insert(any(NursingTask.class))).thenAnswer(insertWithId(ROW_ID));

        NursingTaskVO vo = service.patrol(7L, VISIT, WARD, "BRACELET-01");

        verify(taskMapper).insert(rowCaptor.capture());
        NursingTask row = rowCaptor.getValue();
        assertThat(row.getTaskType()).isEqualTo(TaskType.PATROL.getCode());
        assertThat(row.getSource()).isEqualTo(TaskSource.MANUAL.getCode());
        assertThat(row.getStatus()).isEqualTo(TaskStatus.COMPLETED.getCode());
        assertThat(row.getAssignedNurse()).isEqualTo("nurse-01");
        // planTime = 打卡时刻（服务器时间，GC25），completedAt 同刻盖章（生而终态）
        assertThat(row.getPlanTime()).isCloseTo(patrolledAt, within(2, ChronoUnit.SECONDS));
        assertThat(row.getCompletedAt()).isCloseTo(patrolledAt, within(2, ChronoUnit.SECONDS));
        assertThat(vo.status()).isEqualTo(TaskStatus.COMPLETED.getCode());
        assertThat(vo.taskNo()).isEqualTo("TK2026092200002");
    }

    // ===================== 补充覆盖锚（JaCoCo service.impl LINE=1.00 名单） =====================

    @Test
    @DisplayName("任务创建 code 显式校验：taskType/source/priority 词表外值拒 NS-1019，不落库（W-22⑦）")
    void createRejectsIllegalEnumCodes() {
        assertThatThrownBy(() -> service.create(request("CLEANING", null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                });
        assertThatThrownBy(() -> service.create(request(null, "AUTO", null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                });
        assertThatThrownBy(() -> service.create(request(null, null, "URGENT")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1019");
                });
        verifyNoInteractions(seqGate, events);
        verify(taskMapper, never()).insert(any(NursingTask.class));
    }

    @Test
    @DisplayName("任务创建唯一冲突兜底：任务号唯一键冲突转 NS-1016 幂等拒绝，不发事件（Task 4/5/6 同口径）")
    void createRejectsDuplicateTaskNo() {
        when(seqGate.nextNo("TK")).thenReturn(TASK_NO);
        when(taskMapper.insert(any(NursingTask.class))).thenThrow(new DuplicateKeyException("uk_nursing_task_no"));

        assertThatThrownBy(() -> service.create(request(null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(events, never()).publishEvent(any(NursingDomainEvent.class));
    }

    @Test
    @DisplayName("完成回读缺失：CAS 命中后行被并发逻辑删（selectOne 空）拒 NS-1016，不发事件")
    void completeFailsWhenRowVanishesAfterCas() {
        when(taskMapper.casComplete(TASK_NO, "nurse-01")).thenReturn(1);
        when(taskMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.complete(TASK_NO)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
            assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
        });
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("取消状态违例：CAS 0 行（不存在或已终态）拒 NS-1011，零后续副作用")
    void cancelRejectsAlreadyTerminalTask() {
        when(taskMapper.casCancel(TASK_NO, "患者病情好转", "nurse-01")).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(TASK_NO, new NursingTaskCancelRequest("患者病情好转")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.TASK_STATE_NOT_ALLOWED);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1011");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(taskMapper, never()).selectOne(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("巡视打卡唯一冲突兜底：任务号唯一键冲突转 NS-1016 幂等拒绝（PDA 连点防重）")
    void patrolRejectsDuplicateTaskNo() {
        when(seqGate.nextNo("TK")).thenReturn(TASK_NO);
        when(taskMapper.insert(any(NursingTask.class))).thenThrow(new DuplicateKeyException("uk_nursing_task_no"));

        assertThatThrownBy(() -> service.patrol(7L, VISIT, WARD, "BRACELET-01"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                });
    }

    @Test
    @DisplayName("巡视打卡标识留痕分流：I 型腕带码原值落 source_ref（非敏感）；证件号/短标识落尾四位掩码" + "（敏感字段明文禁落库，V805 列注释口径 + 等保红线）")
    void patrolSourcesRefTrailByIdentifierForm() {
        when(seqGate.nextNo("TK")).thenReturn("TK2026092200002");
        when(taskMapper.insert(any(NursingTask.class))).thenAnswer(insertWithId(ROW_ID));

        // 三形态各打一次卡：I 型腕带就诊编码 / 18 位证件号 / ≤4 位短标识
        service.patrol(7L, VISIT, WARD, "I2026091600001");
        service.patrol(7L, VISIT, WARD, "11010119900101123X");
        service.patrol(7L, VISIT, WARD, "AB12");

        verify(taskMapper, times(3)).insert(rowCaptor.capture());
        List<NursingTask> rows = rowCaptor.getAllValues();
        // I 型腕带就诊编码（CF-3 冻结结构，visitId 形态非敏感）：原值留痕可追溯打卡介质
        assertThat(rows.get(0).getSourceRef()).isEqualTo("I2026091600001");
        // 证件号形态属敏感字段：明文禁落 source_ref，落尾四位掩码值
        assertThat(rows.get(1).getSourceRef()).isEqualTo("****123X");
        // ≤4 位短标识：全星回退（identifierTail 口径，与 PdaServiceImpl 同源）
        assertThat(rows.get(2).getSourceRef()).isEqualTo("****");
    }

    // ===================== 测试数据与断言辅助 =====================

    /**
     * 创建请求替身：taskType/source/priority 传 null 时用合法缺省值填充（MEDICATION/MANUAL/NORMAL），
     * 便于单点验证某一 code 的非法分支。
     */
    private NursingTaskCreateRequest request(String taskType, String source, String priority) {
        return new NursingTaskCreateRequest(
                7L,
                VISIT,
                WARD,
                "01",
                taskType == null ? TaskType.MEDICATION.getCode() : taskType,
                source,
                null,
                OffsetDateTime.now().plusHours(2),
                null,
                priority);
    }

    /** 任务行替身（可指定状态与计划时间；逾期动作列零值起步）。 */
    private NursingTask taskRow(String taskNo, TaskStatus status, OffsetDateTime planTime) {
        NursingTask row = new NursingTask();
        row.setId(ROW_ID);
        row.setTaskNo(taskNo);
        row.setPatientId(7L);
        row.setVisitId(VISIT);
        row.setWardId(WARD);
        row.setTaskType(TaskType.MEDICATION.getCode());
        row.setSource(TaskSource.MANUAL.getCode());
        row.setPlanTime(planTime);
        row.setPriority(TaskPriority.NORMAL.getCode());
        row.setOverdueFlag(false);
        row.setEscalationCount(0);
        row.setStatus(status.getCode());
        return row;
    }

    /** 完成后行替身（读后写替身：CAS 命中后的回读态，completedAt 已盖章）。 */
    private NursingTask completedRow() {
        NursingTask row =
                taskRow(TASK_NO, TaskStatus.COMPLETED, OffsetDateTime.now().minusMinutes(10));
        row.setCompletedAt(OffsetDateTime.now());
        return row;
    }

    /** 取消后行替身（读后写替身：CANCELLED + 原因留痕）。 */
    private NursingTask cancelledRow() {
        NursingTask row =
                taskRow(TASK_NO, TaskStatus.CANCELLED, OffsetDateTime.now().minusMinutes(10));
        row.setCancelReason("患者病情好转");
        return row;
    }

    /** insert 桩：回填固定 id 并返回影响行数 1。 */
    private org.mockito.stubbing.Answer<Integer> insertWithId(long id) {
        return inv -> {
            inv.getArgument(0, NursingTask.class).setId(id);
            return 1;
        };
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表）。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<NursingTask> rendered(Wrapper<NursingTask> captured) {
        LambdaQueryWrapper<NursingTask> wrapper = (LambdaQueryWrapper<NursingTask>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }

    /**
     * 直读 mapper 方法 @Update 注解 SQL（GC26 可执行锚：条件更新必须为注解 SQL 承载）。
     *
     * @param method     mapper 方法名
     * @param paramTypes 方法参数类型（重载定位）
     * @return 拼接后的注解 SQL 全文
     */
    private String recordSql(String method, Class<?>... paramTypes) {
        try {
            Update update =
                    NursingTaskMapper.class.getMethod(method, paramTypes).getAnnotation(Update.class);
            assertThat(update).as("条件更新必须为 @Update 注解 SQL（GC26）").isNotNull();
            return String.join("", update.value());
        } catch (NoSuchMethodException e) {
            return fail("mapper 方法不存在：" + method, e);
        }
    }
}
