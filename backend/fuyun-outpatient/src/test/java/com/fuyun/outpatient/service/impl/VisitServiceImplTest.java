package com.fuyun.outpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.api.VisitFinishedPayload;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.dto.FinishVisitRequest;
import com.fuyun.outpatient.entity.ClinicOrder;
import com.fuyun.outpatient.entity.QueueTicket;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.entity.VisitStatusLog;
import com.fuyun.outpatient.enums.TicketStatus;
import com.fuyun.outpatient.enums.TicketType;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.internal.OutpatientDomainEvent;
import com.fuyun.outpatient.mapper.ClinicOrderMapper;
import com.fuyun.outpatient.mapper.QueueTicketMapper;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.outpatient.mapper.VisitStatusLogMapper;
import com.fuyun.outpatient.service.ITriageService;
import com.fuyun.outpatient.service.IVisitService;
import com.fuyun.outpatient.vo.DoctorQueueItemVO;
import com.fuyun.outpatient.vo.VisitVO;
import com.fuyun.patient.api.PatientDisplayName;
import com.fuyun.patient.api.PatientNameQuery;
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
import org.springframework.http.HttpStatus;

/**
 * 门诊医生站就诊服务单测（M03 FU-M03-05，Task 8 冻结用例集 6 例 + 守卫用例）：接诊（票 CALLED→
 * SERVING 分诊域联动+visit WAITING→IN_CONSULT+admitted_at+每迁必记）、诊毕（在途单据显式确认
 * OP-1016、去向词表 OP-1018、visit.finished 发布断言）、候诊列表（脱敏+优先级降序）。守卫用例
 * 承载 OP-1001/OP-1011 状态机违例与并发 CAS 落败分支行覆盖（service.impl 包 LINE=1.00 门禁）。
 * brief 冻结用例 finishedVisitRejectsNewOrder（红线 5 经 create 入口断言）归
 * ClinicOrderServiceImplTest（create 入口所在测试类）。
 */
@ExtendWith(MockitoExtension.class)
class VisitServiceImplTest {

    @Mock
    private VisitMapper visitMapper;

    @Mock
    private VisitStatusLogMapper visitStatusLogMapper;

    @Mock
    private QueueTicketMapper queueTicketMapper;

    @Mock
    private ClinicOrderMapper clinicOrderMapper;

    @Mock
    private ITriageService triageService;

    @Mock
    private PatientNameQuery patientNameQuery;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<VisitStatusLog> statusLogCaptor;

    @Captor
    private ArgumentCaptor<OutpatientDomainEvent> eventCaptor;

    private IVisitService service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件列解析依赖 TableInfo（容器外单测手动初始化一次：visit/票/申请单三实体）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Visit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QueueTicket.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ClinicOrder.class);
    }

    @BeforeEach
    void setUp() {
        service = new VisitServiceImpl(
                visitMapper,
                visitStatusLogMapper,
                queueTicketMapper,
                clinicOrderMapper,
                triageService,
                patientNameQuery,
                events);
        OperatorContextHolder.set("9001");
    }

    @AfterEach
    void tearDown() {
        OperatorContextHolder.clear();
    }

    // ---------------------------------------------------------------- 替身构造

    /**
     * 就诊记录替身（id=77，patientId=9，DEP001，visitId=O2026092100001）。
     *
     * @param status 就诊状态
     * @return visit 替身，非空
     */
    private Visit visit(VisitStatus status) {
        Visit visit = new Visit();
        visit.setId(77L);
        visit.setVisitId("O2026092100001");
        visit.setPatientId(9L);
        visit.setDeptCode("DEP001");
        visit.setDoctorId("9001");
        visit.setStatus(status);
        visit.setRegisteredAt(OffsetDateTime.now());
        return visit;
    }

    /**
     * 候诊票据替身（visitId=O2026092100001，queueId=DEP001）。
     *
     * @param id     票据主键
     * @param no     票号
     * @param score  优先级分
     * @param status 票据状态
     * @return 票据替身，非空
     */
    private QueueTicket ticket(long id, String visitId, String no, int score, TicketStatus status) {
        QueueTicket ticket = new QueueTicket();
        ticket.setId(id);
        ticket.setVisitId(visitId);
        ticket.setQueueId("DEP001");
        ticket.setTicketNo(no);
        ticket.setTicketType(TicketType.FIRST);
        ticket.setPriorityScore(score);
        ticket.setStatus(status);
        return ticket;
    }

    // ---------------------------------------------------------------- 冻结用例

    @Test
    @DisplayName("接诊：票 CALLED→SERVING+serve_time 分诊域联动+visit IN_CONSULT+admitted_at+迁移留痕一行")
    void admitMarksServingAndMovesVisitToInConsult() {
        Visit visit = visit(VisitStatus.WAITING);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(visitMapper.casAdmit(77L, "WAITING", "IN_CONSULT", "9001")).thenReturn(1);

        VisitVO vo = service.admit("O2026092100001");

        // 票据联动（CALLED→SERVING+serve_time 归分诊域写路径）+visit 专用 CAS（状态迁移+admitted_at
        // 库端 now() 回填单条 UPDATE，与票面 serve_time 同源禁应用时钟——R1 修）
        verify(triageService).markServing("O2026092100001");
        verify(visitMapper).casAdmit(77L, "WAITING", "IN_CONSULT", "9001");
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.IN_CONSULT);
        // 每迁必记（红线 5）：WAITING→IN_CONSULT 一行，操作者=接诊医生
        verify(visitStatusLogMapper).insert(statusLogCaptor.capture());
        assertThat(statusLogCaptor.getValue().getFromStatus()).isEqualTo(VisitStatus.WAITING);
        assertThat(statusLogCaptor.getValue().getToStatus()).isEqualTo(VisitStatus.IN_CONSULT);
        assertThat(statusLogCaptor.getValue().getOperator()).isEqualTo("9001");
        assertThat(vo.status()).isEqualTo(VisitStatus.IN_CONSULT);
    }

    @Test
    @DisplayName("接诊拒绝：无已叫号票据（叫号≠接诊守卫）——OP-1013 且 visit 零迁移")
    void admitRejectsWhenNoCalledTicket() {
        Visit visit = visit(VisitStatus.WAITING);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        doThrow(new BizException(
                        OutpatientErrorCode.TICKET_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "接诊须先叫号（无 CALLED 态票据）：visitId=O2026092100001"))
                .when(triageService)
                .markServing("O2026092100001");

        assertThatThrownBy(() -> service.admit("O2026092100001")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.TICKET_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(visitMapper, never()).casAdmit(anyLong(), anyString(), anyString(), anyString());
        verify(visitStatusLogMapper, never()).insert(any(VisitStatusLog.class));
    }

    @Test
    @DisplayName("诊毕拒绝：在途 PENDING_FEE 单且未显式确认——OP-1016 且零迁移零发布")
    void finishRejectsWithPendingOrdersWithoutConfirm() {
        Visit visit = visit(VisitStatus.IN_CONSULT);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(clinicOrderMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.finish("O2026092100001", new FinishVisitRequest("DISCHARGE_HOME", null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.FINISH_CHECK_FAILED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(visitMapper, never()).casFinish(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("诊毕通过：显式确认路径——FINISHED+finished_at+去向/操作者回填+visit.finished 发布")
    void finishPassesWithExplicitConfirm() {
        Visit visit = visit(VisitStatus.IN_CONSULT);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(visitMapper.casFinish(77L, "IN_CONSULT", "FINISHED", "9001", "DISCHARGE_HOME"))
                .thenReturn(1);

        VisitVO vo = service.finish("O2026092100001", new FinishVisitRequest("DISCHARGE_HOME", true));

        // 专用 CAS（状态迁移+finished_at 库端 now()+去向/操作者落列单条 UPDATE——R1 修时钟同源）
        verify(visitMapper).casFinish(77L, "IN_CONSULT", "FINISHED", "9001", "DISCHARGE_HOME");
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.FINISHED);
        // visit.finished 发布断言（disposition/finishOperator 逐字）
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_VISIT_FINISHED);
        VisitFinishedPayload payload =
                (VisitFinishedPayload) eventCaptor.getValue().payload();
        assertThat(payload.visitId()).isEqualTo("O2026092100001");
        assertThat(payload.patientId()).isEqualTo(9L);
        assertThat(payload.disposition()).isEqualTo("DISCHARGE_HOME");
        assertThat(payload.finishOperator()).isEqualTo("9001");
        assertThat(vo.status()).isEqualTo(VisitStatus.FINISHED);
    }

    @Test
    @DisplayName("诊毕拒绝：去向词表外（CHARGE_BACK）——OP-1018 且零迁移")
    void finishRejectsUnknownDisposition() {
        Visit visit = visit(VisitStatus.IN_CONSULT);
        when(visitMapper.selectOne(any())).thenReturn(visit);

        assertThatThrownBy(() -> service.finish("O2026092100001", new FinishVisitRequest("CHARGE_BACK", true)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.DISPOSITION_INVALID);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(clinicOrderMapper, never()).selectCount(any());
        verify(visitMapper, never()).casFinish(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("候诊列表：姓名掩码+票号+优先级降序+过敏声明位恒 false")
    void patientQueueReturnsMaskedWaitingList() {
        QueueTicket high = ticket(502L, "O2026092100001", "A002", 300, TicketStatus.CALLED);
        QueueTicket low = ticket(501L, "O2026092100002", "A001", 100, TicketStatus.WAITING);
        when(queueTicketMapper.selectList(any())).thenReturn(List.of(high, low));
        Visit second = visit(VisitStatus.WAITING);
        second.setVisitId("O2026092100002");
        second.setPatientId(10L);
        when(visitMapper.selectList(any())).thenReturn(List.of(visit(VisitStatus.WAITING), second));
        when(patientNameQuery.displayNamesOf(any()))
                .thenReturn(List.of(new PatientDisplayName(9L, "张*"), new PatientDisplayName(10L, "李*四")));

        List<DoctorQueueItemVO> queue = service.patientQueue("DEP001", "9001");

        assertThat(queue).hasSize(2);
        // 优先级降序：300 分 CALLED 票在前（库端权威序替身直给）
        assertThat(queue.get(0).priorityScore()).isEqualTo(300);
        assertThat(queue.get(0).ticketNo()).isEqualTo("A002");
        assertThat(queue.get(0).patientName()).isEqualTo("张*");
        assertThat(queue.get(0).allergyFlag()).isFalse();
        assertThat(queue.get(1).ticketNo()).isEqualTo("A001");
        assertThat(queue.get(1).patientName()).isEqualTo("李*四");
        verify(patientNameQuery).displayNamesOf(any());
    }

    @Test
    @DisplayName("候诊列表空队：零票据返回空列表（空映射分支）")
    void patientQueueReturnsEmptyListWhenQueueEmpty() {
        when(queueTicketMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.patientQueue("DEP001", "9001")).isEmpty();
        verifyNoInteractions(patientNameQuery);
    }

    // ---------------------------------------------------------------- 守卫用例（LINE=1.00 分支覆盖）

    @Test
    @DisplayName("接诊拒绝：就诊不存在——OP-1001")
    void admitRejectsUnknownVisit() {
        when(visitMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.admit("O2026092199999"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(OutpatientErrorCode.VISIT_NOT_FOUND));
        verifyNoInteractions(triageService);
    }

    @Test
    @DisplayName("接诊拒绝：visit 未报到（REGISTERED）状态机违例——OP-1011 且零票据联动")
    void admitRejectsWhenVisitNotWaiting() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.REGISTERED));

        assertThatThrownBy(() -> service.admit("O2026092100001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(OutpatientErrorCode.VISIT_STATE_NOT_ALLOWED));
        verifyNoInteractions(triageService);
        verify(visitMapper, never()).casAdmit(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("接诊拒绝：visit CAS 并发落败——OP-1011 且零留痕")
    void admitRejectsWhenVisitCasConcurrentLose() {
        Visit visit = visit(VisitStatus.WAITING);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(visitMapper.casAdmit(77L, "WAITING", "IN_CONSULT", "9001")).thenReturn(0);

        assertThatThrownBy(() -> service.admit("O2026092100001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(OutpatientErrorCode.VISIT_STATE_NOT_ALLOWED));
        verify(triageService).markServing("O2026092100001");
        verify(visitStatusLogMapper, never()).insert(any(VisitStatusLog.class));
    }

    @Test
    @DisplayName("诊毕拒绝：就诊不存在——OP-1001")
    void finishRejectsUnknownVisit() {
        when(visitMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.finish("O2026092199999", new FinishVisitRequest("OTHER", null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(OutpatientErrorCode.VISIT_NOT_FOUND));
    }

    @Test
    @DisplayName("诊毕拒绝：visit REGISTERED→FINISHED 状态机违例——OP-1011")
    void finishRejectsWhenVisitStateIllegal() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.REGISTERED));

        assertThatThrownBy(() -> service.finish("O2026092100001", new FinishVisitRequest("OTHER", true)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(OutpatientErrorCode.VISIT_STATE_NOT_ALLOWED));
        verify(visitMapper, never()).casFinish(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("诊毕拒绝：visit CAS 并发落败——OP-1011 且零发布零留痕")
    void finishRejectsWhenVisitCasConcurrentLose() {
        Visit visit = visit(VisitStatus.IN_CONSULT);
        when(visitMapper.selectOne(any())).thenReturn(visit);
        when(visitMapper.casFinish(77L, "IN_CONSULT", "FINISHED", "9001", "OTHER"))
                .thenReturn(0);

        assertThatThrownBy(() -> service.finish("O2026092100001", new FinishVisitRequest("OTHER", true)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(OutpatientErrorCode.VISIT_STATE_NOT_ALLOWED));
        verify(visitStatusLogMapper, never()).insert(any(VisitStatusLog.class));
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }
}
