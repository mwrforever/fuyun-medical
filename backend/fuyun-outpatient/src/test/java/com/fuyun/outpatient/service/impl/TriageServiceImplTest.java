package com.fuyun.outpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.cache.QueueZsetStore;
import com.fuyun.outpatient.dto.CheckInRequest;
import com.fuyun.outpatient.dto.QueueCallRequest;
import com.fuyun.outpatient.dto.TriageAdjustRequest;
import com.fuyun.outpatient.entity.QueueTicket;
import com.fuyun.outpatient.entity.Schedule;
import com.fuyun.outpatient.entity.TriageRecord;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.entity.VisitStatusLog;
import com.fuyun.outpatient.enums.TicketStatus;
import com.fuyun.outpatient.enums.TicketType;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.mapper.QueueTicketMapper;
import com.fuyun.outpatient.mapper.ScheduleMapper;
import com.fuyun.outpatient.mapper.TriageRecordMapper;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.outpatient.mapper.VisitStatusLogMapper;
import com.fuyun.outpatient.service.ITriageService;
import com.fuyun.outpatient.vo.QueueCalledNotice;
import com.fuyun.outpatient.vo.QueueTicketVO;
import com.fuyun.patient.api.PatientDisplayName;
import com.fuyun.patient.api.PatientNameQuery;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * 分诊台与候诊队列服务单测（M03 FU-M03-04，Task 7 冻结用例集 16 例 + 守卫用例；Task 8 追加接诊
 * 联动 markServing 三例）：报到状态迁移与
 * ZSET 入队编码、冻结优先级公式（急诊分级/老幼残跨类叠加/类别分取最高单项/封顶 999）、调级重排
 * 不改号、跨队列转接放旧建新、叫号惰性重建与原子出队与双 topic 推送、过号降级重入、重呼、快照
 * 脱敏与同分库端排序。守卫用例承载 OP-1001/OP-1012/OP-1013/OP-1019 分支行覆盖
 * （service.impl 包 LINE=1.00 门禁）。Redis/Lua 脚本本体语义由 QueueZsetStoreTest 与真栈 IT 验证。
 */
@ExtendWith(MockitoExtension.class)
class TriageServiceImplTest {

    /** ZSET score 编码基数（与主类同源：priority_score*1e8+queue_seq） */
    private static final long SCORE_ENCODE_SCALE = 100_000_000L;

    @Mock
    private VisitMapper visitMapper;

    @Mock
    private VisitStatusLogMapper visitStatusLogMapper;

    @Mock
    private QueueTicketMapper queueTicketMapper;

    @Mock
    private TriageRecordMapper triageRecordMapper;

    @Mock
    private ScheduleMapper scheduleMapper;

    @Mock
    private QueueZsetStore queueZsetStore;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private PatientNameQuery patientNameQuery;

    @Captor
    private ArgumentCaptor<QueueTicket> ticketCaptor;

    @Captor
    private ArgumentCaptor<TriageRecord> triageCaptor;

    @Captor
    private ArgumentCaptor<VisitStatusLog> statusLogCaptor;

    @Captor
    private ArgumentCaptor<Object> noticeCaptor;

    @Captor
    private ArgumentCaptor<Map<Long, Long>> scoreMapCaptor;

    @Captor
    private ArgumentCaptor<LambdaQueryWrapper<QueueTicket>> wrapperCaptor;

    private ITriageService service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件列解析依赖 TableInfo（容器外单测手动初始化一次：visit/票/排班三实体）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Visit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QueueTicket.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Schedule.class);
    }

    @BeforeEach
    void setUp() {
        service = new TriageServiceImpl(
                visitMapper,
                visitStatusLogMapper,
                queueTicketMapper,
                triageRecordMapper,
                scheduleMapper,
                queueZsetStore,
                redisTemplate,
                messagingTemplate,
                patientNameQuery);
        // 队列当日序签发的公共底座（lenient：仅报到/转队列路径触达，call 系用例不使用不报严格桩告警）
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.increment(anyString())).thenReturn(1L);
        OperatorContextHolder.set("nurse001");
    }

    @AfterEach
    void tearDown() {
        OperatorContextHolder.clear();
    }

    // ---------------------------------------------------------------- 替身构造

    /**
     * 就诊记录替身（id=77，patientId=9，DEP001，visitId=O2026092100001）。
     *
     * @param status      就诊状态
     * @param triageLevel 急诊分级（可空）
     * @param isRevisit   是否复诊（0/1）
     * @return visit 替身，非空
     */
    private Visit visit(VisitStatus status, Integer triageLevel, short isRevisit) {
        Visit visit = new Visit();
        visit.setId(77L);
        visit.setVisitId("O2026092100001");
        visit.setPatientId(9L);
        visit.setDeptCode("DEP001");
        visit.setStatus(status);
        visit.setTriageLevel(triageLevel);
        visit.setIsRevisit(isRevisit);
        return visit;
    }

    /**
     * 候诊票据替身（visitId=O2026092100001，queueId=DEP001，ticketNo=A001）。
     *
     * @param id     票据主键
     * @param type   票别
     * @param score  优先级分
     * @param seq    队列当日序
     * @param status 票据状态
     * @param called 已叫次数
     * @return 票据替身，非空
     */
    private QueueTicket ticket(long id, TicketType type, int score, int seq, TicketStatus status, Integer called) {
        QueueTicket ticket = new QueueTicket();
        ticket.setId(id);
        ticket.setVisitId("O2026092100001");
        ticket.setQueueId("DEP001");
        ticket.setTicketNo("A001");
        ticket.setTicketType(type);
        ticket.setPriorityScore(score);
        ticket.setQueueSeq(seq);
        ticket.setStatus(status);
        ticket.setCalledCount(called);
        return ticket;
    }

    /** 建票 insert 桩：按调用序模拟 MP ASSIGN_ID 回填主键。 */
    private void stubInsertAssignsIds(long... ids) {
        AtomicInteger cursor = new AtomicInteger();
        doAnswer(inv -> {
                    QueueTicket t = inv.getArgument(0);
                    t.setId(ids[cursor.getAndIncrement()]);
                    return 1;
                })
                .when(queueTicketMapper)
                .insert(any(QueueTicket.class));
    }

    // ---------------------------------------------------------------- 冻结用例（16）

    @Test
    @DisplayName("报到：visit CAS REGISTERED→WAITING+建票 priority_score=100（无因子）+ZSET score=100*1e8+seq")
    void checkInMovesVisitToWaitingAndEnqueues() {
        Visit visit = visit(VisitStatus.REGISTERED, null, (short) 0);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(visitMapper.casStatus(77L, "REGISTERED", "WAITING")).thenReturn(1);
        stubInsertAssignsIds(501L);

        QueueTicketVO vo = service.checkIn(new CheckInRequest("O2026092100001", "STATION-01", null));

        // visit 状态迁移（红线 5 合法迁移对）+ 每迁必记 visit_status_log
        verify(visitMapper).casStatus(77L, "REGISTERED", "WAITING");
        verify(visitStatusLogMapper).insert(statusLogCaptor.capture());
        assertThat(statusLogCaptor.getValue().getFromStatus()).isEqualTo(VisitStatus.REGISTERED);
        assertThat(statusLogCaptor.getValue().getToStatus()).isEqualTo(VisitStatus.WAITING);
        // 建票：基础分 100（无分级/复诊/因子）+票号 A+当日序
        verify(queueTicketMapper).insert(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().getPriorityScore()).isEqualTo(100);
        assertThat(ticketCaptor.getValue().getTicketNo()).isEqualTo("A001");
        assertThat(ticketCaptor.getValue().getQueueSeq()).isEqualTo(1);
        assertThat(ticketCaptor.getValue().getTicketType()).isEqualTo(TicketType.FIRST);
        assertThat(ticketCaptor.getValue().getStatus()).isEqualTo(TicketStatus.WAITING);
        // ZSET 入队编码=100*1e8+seq
        verify(queueZsetStore).enqueue("DEP001", 501L, 100 * SCORE_ENCODE_SCALE + 1);
        // 留痕：CHECK_IN 动作+报到终端标识+无因子 JSON
        verify(triageRecordMapper).insert(triageCaptor.capture());
        assertThat(triageCaptor.getValue().getAction().getCode()).isEqualTo("CHECK_IN");
        assertThat(triageCaptor.getValue().getStationId()).isEqualTo("STATION-01");
        assertThat(triageCaptor.getValue().getNurseId()).isEqualTo("nurse001");
        assertThat(triageCaptor.getValue().getPriorityFactor()).isNull();
        // 报到时间回填（国标）；实体补写同值断言：CAS 后禁携 CAS 前旧态经 updateById 覆写状态机
        // （真栈 IT 实证回归锚——覆写致 visit 恒 REGISTERED、admit 恒 OP-1011）
        ArgumentCaptor<Visit> checkInVisitCaptor = ArgumentCaptor.forClass(Visit.class);
        verify(visitMapper).updateById(checkInVisitCaptor.capture());
        assertThat(checkInVisitCaptor.getValue().getStatus()).isEqualTo(VisitStatus.WAITING);
        assertThat(visit.getCheckedInAt()).isNotNull();
        assertThat(vo.status()).isEqualTo(TicketStatus.WAITING);
        assertThat(vo.priorityScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("报到：急诊分级 Ⅰ 级（triage_level=1）——priority_score=900（100+800）")
    void checkInComputesEmergencyLevelScore() {
        Visit visit = visit(VisitStatus.REGISTERED, 1, (short) 0);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(visitMapper.casStatus(77L, "REGISTERED", "WAITING")).thenReturn(1);
        when(valueOperations.increment(anyString())).thenReturn(2L);
        stubInsertAssignsIds(501L);

        QueueTicketVO vo = service.checkIn(new CheckInRequest("O2026092100001", "STATION-01", null));

        assertThat(vo.priorityScore()).isEqualTo(900);
        assertThat(vo.ticketNo()).isEqualTo("A002");
        verify(queueZsetStore).enqueue("DEP001", 501L, 900 * SCORE_ENCODE_SCALE + 2);
    }

    @Test
    @DisplayName("报到：老幼残因子+复诊票别——priority_score=600（100+200+300，跨类叠加）")
    void checkInAddsFrailtyAndRevisitFactors() {
        Visit visit = visit(VisitStatus.REGISTERED, null, (short) 1);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(visitMapper.casStatus(77L, "REGISTERED", "WAITING")).thenReturn(1);
        stubInsertAssignsIds(501L);

        QueueTicketVO vo = service.checkIn(new CheckInRequest("O2026092100001", "STATION-01", List.of("ELDERLY")));

        assertThat(vo.priorityScore()).isEqualTo(600);
        assertThat(vo.ticketType()).isEqualTo(TicketType.RETURN);
        // 因子 JSON 留痕（triage_record.priority_factor）
        verify(triageRecordMapper).insert(triageCaptor.capture());
        assertThat(triageCaptor.getValue().getPriorityFactor()).isEqualTo("[\"ELDERLY\"]");
    }

    @Test
    @DisplayName("报到：visit 非 REGISTERED（FINISHED）——OP-1011 拒绝且零建票")
    void checkInRejectsNonRegisteredVisit() {
        Visit visit = visit(VisitStatus.FINISHED, null, (short) 0);
        when(visitMapper.selectOne(any())).thenReturn(visit);

        assertThatThrownBy(() -> service.checkIn(new CheckInRequest("O2026092100001", "STATION-01", null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.VISIT_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(queueTicketMapper, never()).insert(any(QueueTicket.class));
        verify(queueZsetStore, never()).enqueue(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("守卫：报到 visit CAS 并发落败（读时 REGISTERED、CAS 前被并发迁移）——OP-1011 且零建票")
    void checkInRejectsWhenCasConcurrentLose() {
        Visit visit = visit(VisitStatus.REGISTERED, null, (short) 0);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(visitMapper.casStatus(77L, "REGISTERED", "WAITING")).thenReturn(0);

        assertThatThrownBy(() -> service.checkIn(new CheckInRequest("O2026092100001", "STATION-01", null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.VISIT_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(queueTicketMapper, never()).insert(any(QueueTicket.class));
    }

    @Test
    @DisplayName("调级：重算 100→900 且 ticket_no 不变（过号降级重排不改号 Spec :106）")
    void adjustRecomputesScoreWithoutChangingTicketNo() {
        Visit visit = visit(VisitStatus.WAITING, null, (short) 0);
        QueueTicket ticket = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.WAITING, 0);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(queueTicketMapper.selectOne(any())).thenReturn(ticket);

        QueueTicketVO vo =
                service.adjust(new TriageAdjustRequest("O2026092100001", "LEVEL_ADJUST", null, null, 1, null));

        // 分值重算+ZSET 重排（同 member 换分），票号不变
        verify(queueTicketMapper).updateById(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().getPriorityScore()).isEqualTo(900);
        assertThat(ticketCaptor.getValue().getTicketNo()).isEqualTo("A001");
        verify(queueZsetStore).remove("DEP001", 501L);
        verify(queueZsetStore).enqueue("DEP001", 501L, 900 * SCORE_ENCODE_SCALE + 1);
        // 分级回写 visit（国标字段分诊台写入）
        verify(visitMapper).updateById(any(Visit.class));
        assertThat(visit.getTriageLevel()).isEqualTo(1);
        assertThat(vo.priorityScore()).isEqualTo(900);
        // 留痕：LEVEL_ADJUST 动作
        verify(triageRecordMapper).insert(triageCaptor.capture());
        assertThat(triageCaptor.getValue().getAction().getCode()).isEqualTo("LEVEL_ADJUST");
        assertThat(triageCaptor.getValue().getTriageLevel()).isEqualTo(1);
    }

    @Test
    @DisplayName("转队列：旧 ZSET remove+旧票 CANCELLED+新队新票（uk_ticket_queue 新 queue_id）")
    void adjustTransfersQueueRebuildsTicket() {
        Visit visit = visit(VisitStatus.WAITING, null, (short) 0);
        QueueTicket old = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.WAITING, 0);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(queueTicketMapper.selectOne(any())).thenReturn(old);
        when(queueTicketMapper.casStatus(501L, "WAITING", "CANCELLED", "nurse001"))
                .thenReturn(1);
        stubInsertAssignsIds(601L);

        QueueTicketVO vo =
                service.adjust(new TriageAdjustRequest("O2026092100001", "QUEUE_TRANSFER", "DEP002", null, null, null));

        // 旧票 CAS 放票+旧队移除
        verify(queueTicketMapper).casStatus(501L, "WAITING", "CANCELLED", "nurse001");
        verify(queueZsetStore).remove("DEP001", 501L);
        // 新队新票：新 queue_id/票号/当日序（uk_ticket_visit 以 (visit_id, queue_seq) 区分）
        verify(queueTicketMapper).insert(ticketCaptor.capture());
        QueueTicket fresh = ticketCaptor.getValue();
        assertThat(fresh.getQueueId()).isEqualTo("DEP002");
        assertThat(fresh.getTicketNo()).isEqualTo("A001");
        assertThat(fresh.getQueueSeq()).isEqualTo(1);
        assertThat(fresh.getPriorityScore()).isEqualTo(100);
        verify(queueZsetStore).enqueue("DEP002", 601L, 100 * SCORE_ENCODE_SCALE + 1);
        assertThat(vo.queueId()).isEqualTo("DEP002");
        // 留痕：QUEUE_TRANSFER 动作目标队列
        verify(triageRecordMapper).insert(triageCaptor.capture());
        assertThat(triageCaptor.getValue().getAction().getCode()).isEqualTo("QUEUE_TRANSFER");
        assertThat(triageCaptor.getValue().getTargetQueue()).isEqualTo("DEP002");
    }

    @Test
    @DisplayName("叫号：doctor 指派匹配首票 CALLED+called_count=1+双 topic WS 推送（destination 与载荷断言）")
    void callPollsTopMatchingDoctorAndMarksCalled() {
        QueueTicket first = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.WAITING, 0);
        when(queueTicketMapper.selectWaiting("DEP001")).thenReturn(List.of(first));
        when(queueZsetStore.rebuildIfMissing(eq("DEP001"), any())).thenReturn(-1);
        when(queueZsetStore.pollTop("DEP001", "DOC001")).thenReturn(501L);
        when(queueTicketMapper.selectById(501L)).thenReturn(first);
        when(queueTicketMapper.casCall(501L, "WAITING", "nurse001")).thenReturn(1);
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.WAITING, null, (short) 0));
        when(patientNameQuery.displayNamesOf(anyCollection())).thenReturn(List.of(new PatientDisplayName(9L, "张*")));

        QueueTicketVO vo = service.call(new QueueCallRequest("DEP001", "DOC001"));

        // 叫号 CAS（WAITING→CALLED+called_count 累加）
        verify(queueTicketMapper).casCall(501L, "WAITING", "nurse001");
        assertThat(vo.status()).isEqualTo(TicketStatus.CALLED);
        assertThat(vo.calledCount()).isEqualTo(1);
        // 双 topic 推送：destination 与载荷逐项断言（脱敏姓名+ticketNo，无 visitId/patientId）
        verify(messagingTemplate).convertAndSend(eq("/topic/outpatient/queue/DEP001"), noticeCaptor.capture());
        verify(messagingTemplate).convertAndSend(eq("/topic/outpatient/doctor/DOC001"), noticeCaptor.capture());
        QueueCalledNotice notice = (QueueCalledNotice) noticeCaptor.getValue();
        assertThat(notice.type()).isEqualTo("CALLED");
        assertThat(notice.ticketNo()).isEqualTo("A001");
        assertThat(notice.patientName()).isEqualTo("张*");
        assertThat(notice.doctorId()).isEqualTo("DOC001");
        assertThat(notice.room()).isNull();
    }

    @Test
    @DisplayName("叫号：队列空/无可叫票——null 返回（200 空语义）且零 CAS 零推送")
    void callReturnsNullWhenQueueEmpty() {
        when(queueTicketMapper.selectWaiting("DEP001")).thenReturn(List.of());
        when(queueZsetStore.rebuildIfMissing(eq("DEP001"), any())).thenReturn(-1);
        when(queueZsetStore.pollTop("DEP001", "DOC001")).thenReturn(null);

        assertThat(service.call(new QueueCallRequest("DEP001", "DOC001"))).isNull();

        verify(queueTicketMapper, never()).casCall(anyLong(), anyString(), anyString());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(QueueCalledNotice.class));
    }

    @Test
    @DisplayName("过号：PASSED 后 ZSET 以 priority_score-100 降级分重入（下限 0；分列与票号不变）")
    void passDemotesAndRequeues() {
        QueueTicket ticket = ticket(501L, TicketType.FIRST, 100, 3, TicketStatus.CALLED, 1);
        when(queueTicketMapper.selectById(501L)).thenReturn(ticket);
        when(queueTicketMapper.casStatus(501L, "CALLED", "PASSED", "nurse001")).thenReturn(1);

        QueueTicketVO vo = service.pass(501L);

        verify(queueTicketMapper).casStatus(501L, "CALLED", "PASSED", "nurse001");
        // 降级分=max(0, 100-100)=0 → 编码=0*1e8+3（下限 0 场景）；优先级分列保持 100 不变
        verify(queueZsetStore).enqueue("DEP001", 501L, 3L);
        assertThat(vo.status()).isEqualTo(TicketStatus.PASSED);
        assertThat(vo.priorityScore()).isEqualTo(100);
        assertThat(vo.ticketNo()).isEqualTo("A001");
    }

    @Test
    @DisplayName("重呼：PASSED→CALLED 重复叫+called_count=2+按票指派医生双 topic 重复推送")
    void recallReCallsPassedTicket() {
        QueueTicket ticket = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.PASSED, 1);
        ticket.setDoctorId("DOC002");
        when(queueTicketMapper.selectById(501L)).thenReturn(ticket);
        when(queueTicketMapper.casCall(501L, "PASSED", "nurse001")).thenReturn(1);

        QueueTicketVO vo = service.recall(501L);

        verify(queueTicketMapper).casCall(501L, "PASSED", "nurse001");
        assertThat(vo.status()).isEqualTo(TicketStatus.CALLED);
        assertThat(vo.calledCount()).isEqualTo(2);
        verify(messagingTemplate).convertAndSend(eq("/topic/outpatient/queue/DEP001"), any(QueueCalledNotice.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/outpatient/doctor/DOC002"), any(QueueCalledNotice.class));
    }

    @Test
    @DisplayName("快照：患者姓名掩码出网（张*）且无证件号字段（脱敏红线）")
    void snapshotMasksPatientName() {
        QueueTicket first = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.WAITING, 0);
        QueueTicket second = ticket(502L, TicketType.FIRST, 100, 2, TicketStatus.WAITING, 0);
        second.setVisitId("O2026092100002");
        when(queueTicketMapper.selectList(any())).thenReturn(List.of(first, second));
        Visit visitA = visit(VisitStatus.WAITING, null, (short) 0);
        Visit visitB = visit(VisitStatus.WAITING, null, (short) 0);
        visitB.setVisitId("O2026092100002");
        visitB.setPatientId(10L);
        when(visitMapper.selectList(any())).thenReturn(List.of(visitA, visitB));
        when(patientNameQuery.displayNamesOf(anyCollection()))
                .thenReturn(List.of(new PatientDisplayName(9L, "张*"), new PatientDisplayName(10L, "李*")));

        List<QueueTicketVO> snapshot = service.snapshot("DEP001", null);

        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0).patientName()).isEqualTo("张*");
        assertThat(snapshot.get(1).patientName()).isEqualTo("李*");
    }

    @Test
    @DisplayName("重启恢复（Spec :210）：ZSET 键缺失——按待重叫权威行（WAITING+PASSED）重建（编码公式）后正常出队首票")
    void callRebuildsQueueFromWaitingTicketsWhenZsetKeyMissing() {
        QueueTicket first = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.WAITING, 0);
        QueueTicket second = ticket(502L, TicketType.FIRST, 100, 2, TicketStatus.WAITING, 0);
        // PASSED 过号再入票同属待重叫权威行（fix round 1 Important-2 裁决①：重启后不跌出队列）
        QueueTicket passed = ticket(503L, TicketType.FIRST, 100, 3, TicketStatus.PASSED, 1);
        when(queueTicketMapper.selectWaiting("DEP001")).thenReturn(List.of(first, second, passed));
        when(queueZsetStore.rebuildIfMissing(eq("DEP001"), scoreMapCaptor.capture()))
                .thenReturn(3);
        when(queueZsetStore.pollTop("DEP001", "DOC001")).thenReturn(501L);
        when(queueTicketMapper.selectById(501L)).thenReturn(first);
        when(queueTicketMapper.casCall(501L, "WAITING", "nurse001")).thenReturn(1);

        QueueTicketVO vo = service.call(new QueueCallRequest("DEP001", "DOC001"));

        // 重建映射=priority_score*1e8+seq 公式逐票核对（PASSED 票同公式同列值），返回 3=重建三票
        assertThat(scoreMapCaptor.getValue())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        501L,
                        100 * SCORE_ENCODE_SCALE + 1,
                        502L,
                        100 * SCORE_ENCODE_SCALE + 2,
                        503L,
                        100 * SCORE_ENCODE_SCALE + 3));
        assertThat(vo.ticketNo()).isEqualTo("A001");
        verify(queueTicketMapper).casCall(501L, "WAITING", "nurse001");
    }

    @Test
    @DisplayName("队内重叫：出队 PASSED 过号再入票按当前态 CAS→CALLED（called_count 累加+双 topic 推送）")
    void callRepollsPassedTicketFromQueue() {
        QueueTicket passed = ticket(501L, TicketType.FIRST, 100, 3, TicketStatus.PASSED, 1);
        when(queueTicketMapper.selectWaiting("DEP001")).thenReturn(List.of(passed));
        when(queueZsetStore.rebuildIfMissing(eq("DEP001"), any())).thenReturn(-1);
        when(queueZsetStore.pollTop("DEP001", "DOC001")).thenReturn(501L);
        when(queueTicketMapper.selectById(501L)).thenReturn(passed);
        when(queueTicketMapper.casCall(501L, "PASSED", "nurse001")).thenReturn(1);

        QueueTicketVO vo = service.call(new QueueCallRequest("DEP001", "DOC001"));

        // from=票行当前态 PASSED（WAITING 首叫与 PASSED 队内重叫共用 casCall）
        verify(queueTicketMapper).casCall(501L, "PASSED", "nurse001");
        assertThat(vo.status()).isEqualTo(TicketStatus.CALLED);
        assertThat(vo.calledCount()).isEqualTo(2);
        verify(messagingTemplate).convertAndSend(eq("/topic/outpatient/queue/DEP001"), any(QueueCalledNotice.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/outpatient/doctor/DOC001"), any(QueueCalledNotice.class));
    }

    @Test
    @DisplayName("重启恢复幂等：ZSET 键在位——rebuildIfMissing 返回 -1 零重建（正常出队态不回灌）")
    void callSkipsRebuildWhenZsetKeyPresent() {
        QueueTicket first = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.WAITING, 0);
        QueueTicket second = ticket(502L, TicketType.FIRST, 100, 2, TicketStatus.WAITING, 0);
        when(queueTicketMapper.selectWaiting("DEP001")).thenReturn(List.of(first, second));
        when(queueZsetStore.rebuildIfMissing(eq("DEP001"), any())).thenReturn(-1);
        when(queueZsetStore.pollTop("DEP001", "DOC001")).thenReturn(501L);
        when(queueTicketMapper.selectById(501L)).thenReturn(first);
        when(queueTicketMapper.casCall(501L, "WAITING", "nurse001")).thenReturn(1);

        QueueTicketVO vo = service.call(new QueueCallRequest("DEP001", "DOC001"));

        // -1=键在位零重建信号：服务不据此回灌（store 侧零写语义由 QueueZsetStoreTest 承载）
        assertThat(vo.ticketNo()).isEqualTo("A001");
        verify(queueZsetStore, never()).enqueue(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("同分排序（偏差⑨）：快照序=优先级分降序+queue_time 建行时间升序（库端权威，排序入参零应用时钟）")
    void sameScoreTicketsOrderByQueueTimeNotAppClock() {
        when(queueTicketMapper.selectList(wrapperCaptor.capture())).thenReturn(List.of());
        when(patientNameQuery.displayNamesOf(anyCollection())).thenReturn(List.of());

        service.snapshot("DEP001", null);

        LambdaQueryWrapper<QueueTicket> wrapper = wrapperCaptor.getValue();
        // 排序子句：priority_score DESC + queue_time ASC（同分按建行时间升序——queue_time 为库端时间戳）
        assertThat(wrapper.getSqlSegment()).contains("priority_score DESC");
        assertThat(wrapper.getSqlSegment()).contains("queue_time ASC");
        // 零应用服务器时钟取值：条件参数仅队列标识本身（排序只依赖库端列值，无时钟派生入参）
        assertThat(wrapper.getParamNameValuePairs()).containsExactlyEntriesOf(Map.of("MPGENVAL1", "DEP001"));
    }

    @Test
    @DisplayName("跨类叠加（偏差⑨）：分级 600 与回诊 300 并存取最高单项不叠加，老幼残 200 跨类叠加——900")
    void categoryScoreTakesMaxWhileFrailtyOverlays() {
        Visit visit = visit(VisitStatus.REGISTERED, 3, (short) 1);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(visitMapper.casStatus(77L, "REGISTERED", "WAITING")).thenReturn(1);
        stubInsertAssignsIds(501L);

        QueueTicketVO vo = service.checkIn(new CheckInRequest("O2026092100001", "STATION-01", List.of("ELDERLY")));

        // 类别分=max(600 分级, 300 回诊)=600 不叠加；老幼残 200 跨类叠加：100+600+200=900
        assertThat(vo.priorityScore()).isEqualTo(900);
    }

    @Test
    @DisplayName("封顶（偏差⑨）：急诊 800+老幼残 200+基础 100=1100 超顶——priority_score=min(999,1100)=999")
    void priorityScoreCapsAt999WhenFactorsStack() {
        Visit visit = visit(VisitStatus.REGISTERED, 1, (short) 0);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(visitMapper.casStatus(77L, "REGISTERED", "WAITING")).thenReturn(1);
        stubInsertAssignsIds(501L);

        QueueTicketVO vo = service.checkIn(new CheckInRequest("O2026092100001", "STATION-01", List.of("ELDERLY")));

        assertThat(vo.priorityScore()).isEqualTo(999);
        verify(queueZsetStore).enqueue("DEP001", 501L, 999 * SCORE_ENCODE_SCALE + 1);
    }

    // ---------------------------------------------------------------- 守卫用例（LINE=1.00 分支行覆盖）

    @Test
    @DisplayName("守卫：报到就诊号不存在——OP-1001（404）且零迁移")
    void checkInRejectsMissingVisit() {
        when(visitMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.checkIn(new CheckInRequest("O9999999999999", "STATION-01", null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.VISIT_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(visitMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("守卫：报到因子词表外——OP-1019（400）且零迁移（W-22⑦ 禁裸值口径）")
    void checkInRejectsUnknownFactor() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.REGISTERED, null, (short) 0));

        assertThatThrownBy(() -> service.checkIn(new CheckInRequest("O2026092100001", "STATION-01", List.of("TALL"))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(visitMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("守卫：队列当日序 Redis 流水返回空——IllegalStateException（建票无法落号，交上层处置）")
    void checkInRejectsWhenSeqRedisDown() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.REGISTERED, null, (short) 0));
        when(visitMapper.casStatus(77L, "REGISTERED", "WAITING")).thenReturn(1);
        when(valueOperations.increment(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.checkIn(new CheckInRequest("O2026092100001", "STATION-01", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("队列当日序签发失败");
    }

    @Test
    @DisplayName("守卫：分诊动作词表外——OP-1019（400）且零业务读")
    void adjustRejectsUnknownAction() {
        assertThatThrownBy(() ->
                        service.adjust(new TriageAdjustRequest("O2026092100001", "MAGIC", null, null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(visitMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("守卫：adjust 端点误携 CHECK_IN 动作——OP-1019（报到走专用端点）")
    void adjustRejectsCheckInAction() {
        assertThatThrownBy(() ->
                        service.adjust(new TriageAdjustRequest("O2026092100001", "CHECK_IN", null, null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(visitMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("守卫：调整就诊号不存在——OP-1001（404）")
    void adjustRejectsMissingVisit() {
        when(visitMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() ->
                        service.adjust(new TriageAdjustRequest("O9999999999999", "LEVEL_ADJUST", null, null, 1, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.VISIT_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("守卫：调整分级越界（5）——OP-1019（400）且零票查询")
    void adjustRejectsInvalidTriageLevel() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.WAITING, null, (short) 0));

        assertThatThrownBy(() ->
                        service.adjust(new TriageAdjustRequest("O2026092100001", "LEVEL_ADJUST", null, null, 5, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(queueTicketMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("守卫：调整无在队票（未报到/已离队）——OP-1012（404）")
    void adjustRejectsTicketNotFound() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.WAITING, null, (short) 0));
        when(queueTicketMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() ->
                        service.adjust(new TriageAdjustRequest("O2026092100001", "LEVEL_ADJUST", null, null, 1, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.TICKET_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("二次分诊：RE_TRIAGE 定医生——ticket.doctor_id 指派且零 ZSET 重排（分值不变）")
    void adjustRetriageAssignsDoctor() {
        Visit visit = visit(VisitStatus.WAITING, null, (short) 0);
        QueueTicket ticket = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.WAITING, 0);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(queueTicketMapper.selectOne(any())).thenReturn(ticket);

        QueueTicketVO vo =
                service.adjust(new TriageAdjustRequest("O2026092100001", "RE_TRIAGE", null, "DOC009", null, null));

        verify(queueTicketMapper).updateById(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().getDoctorId()).isEqualTo("DOC009");
        verify(queueZsetStore, never()).remove(anyString(), anyLong());
        verify(queueZsetStore, never()).enqueue(anyString(), anyLong(), anyLong());
        assertThat(vo.doctorId()).isEqualTo("DOC009");
        verify(triageRecordMapper).insert(triageCaptor.capture());
        assertThat(triageCaptor.getValue().getAction().getCode()).isEqualTo("RE_TRIAGE");
        assertThat(triageCaptor.getValue().getDoctorId()).isEqualTo("DOC009");
    }

    @Test
    @DisplayName("守卫：转队列缺目标队列——OP-1019（400）且零旧票 CAS 零 Redis 移除")
    void adjustTransferRejectsBlankTargetQueue() {
        Visit visit = visit(VisitStatus.WAITING, null, (short) 0);
        QueueTicket ticket = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.WAITING, 0);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(queueTicketMapper.selectOne(any())).thenReturn(ticket);

        assertThatThrownBy(() -> service.adjust(
                        new TriageAdjustRequest("O2026092100001", "QUEUE_TRANSFER", "  ", null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(queueTicketMapper, never()).casStatus(anyLong(), anyString(), anyString(), anyString());
        verify(queueZsetStore, never()).remove(anyString(), anyLong());
    }

    @Test
    @DisplayName("守卫：转队列旧票 CAS 并发落败——IllegalStateException 且零 Redis 移除（先 CAS 后动 Redis）")
    void adjustTransferConcurrentCasFails() {
        Visit visit = visit(VisitStatus.WAITING, null, (short) 0);
        QueueTicket ticket = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.WAITING, 0);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(queueTicketMapper.selectOne(any())).thenReturn(ticket);
        when(queueTicketMapper.casStatus(501L, "WAITING", "CANCELLED", "nurse001"))
                .thenReturn(0);

        assertThatThrownBy(() -> service.adjust(
                        new TriageAdjustRequest("O2026092100001", "QUEUE_TRANSFER", "DEP002", null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAS 落败");
        verify(queueZsetStore, never()).remove(anyString(), anyLong());
    }

    @Test
    @DisplayName("守卫：叫号出队票行缺失——OP-1012（404）")
    void callRejectsWhenTicketRowMissing() {
        QueueTicket first = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.WAITING, 0);
        when(queueTicketMapper.selectWaiting("DEP001")).thenReturn(List.of(first));
        when(queueZsetStore.rebuildIfMissing(eq("DEP001"), any())).thenReturn(-1);
        when(queueZsetStore.pollTop("DEP001", "DOC001")).thenReturn(501L);
        when(queueTicketMapper.selectById(501L)).thenReturn(null);

        assertThatThrownBy(() -> service.call(new QueueCallRequest("DEP001", "DOC001")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.TICKET_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("守卫：叫号 CAS 并发落败——OP-1013（409）且零推送")
    void callRejectsWhenCasLost() {
        QueueTicket first = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.WAITING, 0);
        when(queueTicketMapper.selectWaiting("DEP001")).thenReturn(List.of(first));
        when(queueZsetStore.rebuildIfMissing(eq("DEP001"), any())).thenReturn(-1);
        when(queueZsetStore.pollTop("DEP001", "DOC001")).thenReturn(501L);
        when(queueTicketMapper.selectById(501L)).thenReturn(first);
        when(queueTicketMapper.casCall(501L, "WAITING", "nurse001")).thenReturn(0);

        assertThatThrownBy(() -> service.call(new QueueCallRequest("DEP001", "DOC001")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.TICKET_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(QueueCalledNotice.class));
    }

    @Test
    @DisplayName("守卫：过号票据不存在——OP-1012（404）")
    void passRejectsMissingTicket() {
        when(queueTicketMapper.selectById(501L)).thenReturn(null);

        assertThatThrownBy(() -> service.pass(501L)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.TICKET_NOT_FOUND);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
    }

    @Test
    @DisplayName("守卫：过号 CAS 落败（非 CALLED/并发迁移）——OP-1013（409）且零 ZSET 重排")
    void passRejectsWhenCasLost() {
        QueueTicket ticket = ticket(501L, TicketType.FIRST, 100, 3, TicketStatus.WAITING, 0);
        when(queueTicketMapper.selectById(501L)).thenReturn(ticket);
        when(queueTicketMapper.casStatus(501L, "CALLED", "PASSED", "nurse001")).thenReturn(0);

        assertThatThrownBy(() -> service.pass(501L)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.TICKET_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(queueZsetStore, never()).enqueue(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("守卫：重呼票据不存在——OP-1012（404）")
    void recallRejectsMissingTicket() {
        when(queueTicketMapper.selectById(501L)).thenReturn(null);

        assertThatThrownBy(() -> service.recall(501L)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.TICKET_NOT_FOUND);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
    }

    @Test
    @DisplayName("守卫：重呼 CAS 落败（非 PASSED/并发迁移）——OP-1013（409）且零推送")
    void recallRejectsWhenCasLost() {
        QueueTicket ticket = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.CALLED, 1);
        when(queueTicketMapper.selectById(501L)).thenReturn(ticket);
        when(queueTicketMapper.casCall(501L, "PASSED", "nurse001")).thenReturn(0);

        assertThatThrownBy(() -> service.recall(501L)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.TICKET_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(QueueCalledNotice.class));
    }

    @Test
    @DisplayName("守卫：快照状态词表外——OP-1019（400）")
    void snapshotRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.snapshot("DEP001", "MAGIC")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
    }

    @Test
    @DisplayName("快照状态过滤：status 参数入 wrapper 条件（paramNameValuePairs 携带 WAITING）")
    void snapshotFiltersByStatus() {
        when(queueTicketMapper.selectList(wrapperCaptor.capture())).thenReturn(List.of());
        when(patientNameQuery.displayNamesOf(anyCollection())).thenReturn(List.of());

        service.snapshot("DEP001", "WAITING");

        LambdaQueryWrapper<QueueTicket> wrapper = wrapperCaptor.getValue();
        // getSqlSegment 触发条件格式化（MP 惰性填充 paramNameValuePairs），随后断言过滤参数
        assertThat(wrapper.getSqlSegment()).contains("status =");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(TicketStatus.WAITING);
    }

    // ---------------------------------------------------------------- 接诊联动（Task 8 markServing）

    @Test
    @DisplayName("接诊联动：CALLED 票 CAS→SERVING+serve_time 回填（casAdmit 单步原子）")
    void markServingTransitionsCalledTicketToServing() {
        QueueTicket ticket = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.CALLED, 1);
        when(queueTicketMapper.selectOne(any())).thenReturn(ticket);
        when(queueTicketMapper.casAdmit(501L, "nurse001")).thenReturn(1);
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.IN_CONSULT, null, (short) 0));
        when(patientNameQuery.displayNamesOf(anyCollection())).thenReturn(List.of(new PatientDisplayName(9L, "张*")));

        QueueTicketVO vo = service.markServing("O2026092100001");

        verify(queueTicketMapper).casAdmit(501L, "nurse001");
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.SERVING);
        assertThat(vo.status()).isEqualTo(TicketStatus.SERVING);
        assertThat(vo.patientName()).isEqualTo("张*");
    }

    @Test
    @DisplayName("接诊拒绝：无 CALLED 票（未叫号/已过号/已接诊）——OP-1013（409）且零 CAS")
    void markServingRejectsWhenNoCalledTicket() {
        when(queueTicketMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.markServing("O2026092100001"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.TICKET_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(queueTicketMapper, never()).casAdmit(anyLong(), anyString());
    }

    @Test
    @DisplayName("接诊拒绝：CAS 并发落败（并发已迁移）——OP-1013（409）")
    void markServingRejectsWhenCasLost() {
        QueueTicket ticket = ticket(501L, TicketType.FIRST, 100, 1, TicketStatus.CALLED, 1);
        when(queueTicketMapper.selectOne(any())).thenReturn(ticket);
        when(queueTicketMapper.casAdmit(501L, "nurse001")).thenReturn(0);

        assertThatThrownBy(() -> service.markServing("O2026092100001"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.TICKET_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }
}
