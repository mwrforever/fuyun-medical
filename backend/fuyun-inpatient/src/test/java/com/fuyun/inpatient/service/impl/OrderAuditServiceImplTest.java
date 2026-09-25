package com.fuyun.inpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.OrderAuditRejectedPayload;
import com.fuyun.inpatient.api.payload.OrderAuditedPayload;
import com.fuyun.inpatient.api.payload.OrderCancelledPayload;
import com.fuyun.inpatient.api.payload.OrderRevokedPayload;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.OrderReorganizeRequest;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.entity.MedicalOrder;
import com.fuyun.inpatient.entity.MedicalOrderItem;
import com.fuyun.inpatient.entity.OrderAudit;
import com.fuyun.inpatient.entity.OrderStatusLog;
import com.fuyun.inpatient.enums.AuditStage;
import com.fuyun.inpatient.enums.OrderStatus;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.mapper.MedicalOrderItemMapper;
import com.fuyun.inpatient.mapper.MedicalOrderMapper;
import com.fuyun.inpatient.mapper.OrderAuditMapper;
import com.fuyun.inpatient.mapper.OrderStatusLogMapper;
import com.fuyun.inpatient.service.OrderStateMachineService;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.http.HttpStatus;

/**
 * 医嘱审核与控制服务单测（Task 6 冻结九用例 + LINE=1.00 覆盖补面）：系统自动审核两分支
 * （非用药过审/用药停留待审）、药师回执通过/驳回迁移与事件、重复回执幂等、作废拦截已执行、
 * 撤回仅转抄前、重整不改状态仅留痕 + 口头医嘱补录确认面 + 守卫补充面（脏类型/操作者非数字/
 * 回执定位失配/时点容错）。MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册
 * 表信息；OrderStateMachineService 按冻结接口 mock（状态面语义由
 * OrderStateMachineServiceImplTest 独立承载）。
 */
@ExtendWith(MockitoExtension.class)
class OrderAuditServiceImplTest {

    /** 编排主体 I 型 14 位就诊号 */
    private static final String VISIT_ID = "I2026092500001";

    /** 就诊行主键（medical_order.visit_id 消费面——非 I 型号） */
    private static final long VISIT_PK = 7001L;

    /** 患者主索引 */
    private static final long PATIENT_ID = 1001L;

    /** 操作者员工 ID（数字形态——执业授权与审计口径） */
    private static final long OPERATOR = 1001L;

    /** 医嘱号 */
    private static final String ORDER_NO = "MO2026092500001";

    /** 审方任务单号（M06 回执载荷 auditNo） */
    private static final String REVIEW_TASK_NO = "RT2026092500001";

    /** 回执审核时点（M06 回执载荷 auditedAt） */
    private static final Instant REPLY_AT = Instant.parse("2026-09-25T08:30:00Z");

    @Mock
    private MedicalOrderMapper orderMapper;

    @Mock
    private MedicalOrderItemMapper itemMapper;

    @Mock
    private OrderAuditMapper auditMapper;

    @Mock
    private OrderStatusLogMapper statusLogMapper;

    @Mock
    private InpatientVisitMapper visitMapper;

    @Mock
    private OrderStateMachineService stateMachine;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<OrderAudit> auditCaptor;

    @Captor
    private ArgumentCaptor<OrderStatusLog> statusLogCaptor;

    @Captor
    private ArgumentCaptor<InpatientDomainEvent> eventCaptor;

    private OrderAuditServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（三实体查询面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MedicalOrder.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MedicalOrderItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InpatientVisit.class);
    }

    @BeforeEach
    void setUp() {
        service = new OrderAuditServiceImpl(
                orderMapper, itemMapper, auditMapper, statusLogMapper, visitMapper, stateMachine, events);
        OperatorContextHolder.set(String.valueOf(OPERATOR));
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("用例①非用药类自动过审：LAB 医嘱 CREATED→AUDITED+生效时点+SYSTEM 审计行+audited.lab 子键事件")
    void auditAutoPassesNonMedicationOrder() {
        MedicalOrder order = orderRow("LAB", OrderStatus.CREATED);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.updateAuditBegin(eq(ORDER_NO), any(), eq(String.valueOf(OPERATOR))))
                .thenReturn(1);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());

        OrderStatus result = service.audit(ORDER_NO);

        // 状态机唯一迁移面 + 生效时点落值（V904 begin_at 审核通过面契约）
        assertThat(result).isEqualTo(OrderStatus.AUDITED);
        verify(stateMachine).transition(order, OrderStatus.AUDITED, "系统自动审核通过", OPERATOR);
        verify(orderMapper).updateAuditBegin(eq(ORDER_NO), any(), eq(String.valueOf(OPERATOR)));
        // SYSTEM 审计行：结论 PASSED、无审方任务引用
        verify(auditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getStage()).isEqualTo(AuditStage.SYSTEM.getCode());
        assertThat(auditCaptor.getValue().getConclusion()).isEqualTo("PASSED");
        assertThat(auditCaptor.getValue().getReviewTaskNo()).isNull();
        assertThat(auditCaptor.getValue().getAuditOperator()).isEqualTo(String.valueOf(OPERATOR));
        // 事件：routing 子键 lab（登记名不带子键），载荷 auditType=SYSTEM、操作者=开立医生
        verify(events).publishEvent(eventCaptor.capture());
        InpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType())
                .isEqualTo(InpatientMessagingConstants.withTypeKey(
                        InpatientMessagingConstants.EVENT_ORDER_AUDITED, "lab"));
        OrderAuditedPayload payload = (OrderAuditedPayload) event.payload();
        assertThat(payload.m04OrderNo()).isEqualTo(ORDER_NO);
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.patientId()).isEqualTo(PATIENT_ID);
        assertThat(payload.auditType()).isEqualTo(AuditStage.SYSTEM.getCode());
        assertThat(payload.auditOperator()).isEqualTo(String.valueOf(OPERATOR));
        assertThat(payload.auditedAt()).isNotNull();
    }

    @Test
    @DisplayName("用例②用药类停留待审：DRUG 医嘱停留 CREATED+SYSTEM 预进行（不迁移不发事件）")
    void auditKeepsMedicationOrderAwaitingPharmacist() {
        MedicalOrder order = orderRow("DRUG", OrderStatus.CREATED);
        when(orderMapper.selectOne(any())).thenReturn(order);

        OrderStatus result = service.audit(ORDER_NO);

        // 用药类停留 CREATED（语义=待药师审，stage 区分不新增状态）：零迁移零事件零生效时点
        assertThat(result).isEqualTo(OrderStatus.CREATED);
        verifyNoInteractions(stateMachine, events);
        verify(orderMapper, never()).updateAuditBegin(anyString(), any(), anyString());
        // SYSTEM 预进行：结论 PASSED、理由声明待药师审方
        verify(auditMapper).insert(auditCaptor.capture());
        OrderAudit row = auditCaptor.getValue();
        assertThat(row.getStage()).isEqualTo(AuditStage.SYSTEM.getCode());
        assertThat(row.getConclusion()).isEqualTo("PASSED");
        assertThat(row.getReason()).contains("待药师审方");
    }

    @Test
    @DisplayName("用例③回执通过迁移：completed 回执→AUDITED+PHARMACIST 审计行（含任务号）+audited.drug 子键事件")
    void pharmacistApprovalMigratesToAuditedAndPublishesDrugRoutedEvent() {
        MedicalOrder order = orderRow("DRUG", OrderStatus.CREATED);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.updateAuditBegin(eq(ORDER_NO), any(), eq("1002"))).thenReturn(1);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());

        service.onPharmacistApproved(ORDER_NO, REVIEW_TASK_NO, "1002", REPLY_AT);

        verify(stateMachine).transition(order, OrderStatus.AUDITED, "药师审方通过", 1002L);
        verify(orderMapper).updateAuditBegin(eq(ORDER_NO), any(), eq("1002"));
        verify(auditMapper).insert(auditCaptor.capture());
        OrderAudit row = auditCaptor.getValue();
        assertThat(row.getStage()).isEqualTo(AuditStage.PHARMACIST.getCode());
        assertThat(row.getConclusion()).isEqualTo("PASSED");
        assertThat(row.getReviewTaskNo()).isEqualTo(REVIEW_TASK_NO);
        assertThat(row.getAuditOperator()).isEqualTo("1002");
        // 事件：drug 子键（M06 回执仅用药类）+ auditType=PHARMACIST + 回执时点
        verify(events).publishEvent(eventCaptor.capture());
        InpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType())
                .isEqualTo(InpatientMessagingConstants.withTypeKey(
                        InpatientMessagingConstants.EVENT_ORDER_AUDITED, "drug"));
        OrderAuditedPayload payload = (OrderAuditedPayload) event.payload();
        assertThat(payload.auditType()).isEqualTo(AuditStage.PHARMACIST.getCode());
        assertThat(payload.auditOperator()).isEqualTo("1002");
        assertThat(payload.auditedAt()).isEqualTo(REPLY_AT);
    }

    @Test
    @DisplayName("用例④回执驳回迁移：rejected 回执→AUDIT_REJECTED+PHARMACIST 驳回行（理由留痕）+audit-rejected 事件")
    void pharmacistRejectionMigratesAndPublishesAuditRejectedEvent() {
        MedicalOrder order = orderRow("DRUG", OrderStatus.CREATED);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());

        service.onPharmacistRejected(ORDER_NO, REVIEW_TASK_NO, "剂量超限，请调整后重提", "1002", REPLY_AT);

        // 驳回原因即迁移留痕原因（医生站重提修改依据）
        verify(stateMachine).transition(order, OrderStatus.AUDIT_REJECTED, "剂量超限，请调整后重提", 1002L);
        verify(auditMapper).insert(auditCaptor.capture());
        OrderAudit row = auditCaptor.getValue();
        assertThat(row.getStage()).isEqualTo(AuditStage.PHARMACIST.getCode());
        assertThat(row.getConclusion()).isEqualTo("REJECTED");
        assertThat(row.getReason()).isEqualTo("剂量超限，请调整后重提");
        // 驳回事件（V901 id 67 载荷：m04OrderNo/visitId/patientId/rejectReason/rejectedAt）
        verify(events).publishEvent(eventCaptor.capture());
        InpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(InpatientMessagingConstants.EVENT_ORDER_AUDIT_REJECTED);
        OrderAuditRejectedPayload payload = (OrderAuditRejectedPayload) event.payload();
        assertThat(payload.m04OrderNo()).isEqualTo(ORDER_NO);
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.patientId()).isEqualTo(PATIENT_ID);
        assertThat(payload.rejectReason()).isEqualTo("剂量超限，请调整后重提");
        assertThat(payload.rejectedAt()).isEqualTo(REPLY_AT);
    }

    @Test
    @DisplayName("用例⑤重复回执幂等：已 AUDITED 的 completed 与已 AUDIT_REJECTED 的 rejected 二次回执零副作用")
    void duplicateRepliesAreIdempotent() {
        // 通过回执重复：医嘱已 AUDITED（首次回执已迁移）——直返零写零事件
        MedicalOrder audited = orderRow("DRUG", OrderStatus.AUDITED);
        when(orderMapper.selectOne(any())).thenReturn(audited);
        service.onPharmacistApproved(ORDER_NO, REVIEW_TASK_NO, "1002", REPLY_AT);
        verifyNoInteractions(stateMachine, auditMapper, events);

        // 驳回回执重复：医嘱已 AUDIT_REJECTED——直返零写零事件
        MedicalOrder rejected = orderRow("DRUG", OrderStatus.AUDIT_REJECTED);
        when(orderMapper.selectOne(any())).thenReturn(rejected);
        service.onPharmacistRejected(ORDER_NO, REVIEW_TASK_NO, "重复驳回", "1002", REPLY_AT);
        verifyNoInteractions(stateMachine, auditMapper, events);
    }

    @Test
    @DisplayName("用例⑦作废拦截已执行：EXECUTING 作废状态机拒 IP-1010 传播，cancelled 事件零发布")
    void cancelRejectsExecutingOrder() {
        MedicalOrder order = orderRow("DRUG", OrderStatus.EXECUTING);
        when(orderMapper.selectOne(any())).thenReturn(order);
        doThrow(new BizException(InpatientErrorCode.ORDER_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "状态机违例"))
                .when(stateMachine)
                .transition(order, OrderStatus.CANCELLED, "开错医嘱", OPERATOR);

        assertThatThrownBy(() -> service.cancel(ORDER_NO, "开错医嘱"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ORDER_STATE_NOT_ALLOWED));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("作废成功面：AUDITED→CANCELLED+cancelled 事件（V800 id 45 载荷：时点/原因）")
    void cancelPublishesCancelledEvent() {
        MedicalOrder order = orderRow("LAB", OrderStatus.AUDITED);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());

        service.cancel(ORDER_NO, "开错医嘱");

        verify(stateMachine).transition(order, OrderStatus.CANCELLED, "开错医嘱", OPERATOR);
        verify(events).publishEvent(eventCaptor.capture());
        OrderCancelledPayload payload =
                (OrderCancelledPayload) eventCaptor.getValue().payload();
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(InpatientMessagingConstants.EVENT_ORDER_CANCELLED);
        assertThat(payload.m04OrderNo()).isEqualTo(ORDER_NO);
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.patientId()).isEqualTo(PATIENT_ID);
        assertThat(payload.cancelledAt()).isNotNull();
        assertThat(payload.cancelReason()).isEqualTo("开错医嘱");
    }

    @Test
    @DisplayName("用例⑧撤回仅转抄前：TRANSFERRED 撤回状态机拒 IP-1010，生效时点复位与事件零触达")
    void revokeAuditRejectsTransferredOrder() {
        MedicalOrder order = orderRow("DRUG", OrderStatus.TRANSFERRED);
        when(orderMapper.selectOne(any())).thenReturn(order);
        doThrow(new BizException(InpatientErrorCode.ORDER_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "状态机违例"))
                .when(stateMachine)
                .transition(order, OrderStatus.CREATED, "撤回审核（转抄前）", OPERATOR);

        assertThatThrownBy(() -> service.revokeAudit(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ORDER_STATE_NOT_ALLOWED));
        verify(orderMapper, never()).updateRevokeAudit(anyString(), anyString());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("撤回成功面：AUDITED→CREATED+生效时点复位零行 IP-1023 守卫+revoked 事件（id 46 载荷）")
    void revokeAuditSuccessAndZeroRowGuard() {
        MedicalOrder order = orderRow("DRUG", OrderStatus.AUDITED);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(orderMapper.updateRevokeAudit(ORDER_NO, String.valueOf(OPERATOR))).thenReturn(1);

        service.revokeAudit(ORDER_NO);

        verify(stateMachine).transition(order, OrderStatus.CREATED, "撤回审核（转抄前）", OPERATOR);
        verify(orderMapper).updateRevokeAudit(ORDER_NO, String.valueOf(OPERATOR));
        verify(events).publishEvent(eventCaptor.capture());
        OrderRevokedPayload payload =
                (OrderRevokedPayload) eventCaptor.getValue().payload();
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(InpatientMessagingConstants.EVENT_ORDER_REVOKED);
        assertThat(payload.m04OrderNo()).isEqualTo(ORDER_NO);
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.revokedAt()).isNotNull();

        // 复位零行（并发逻辑删窗口）：IP-1023，事件不再发布（事务整体回滚语义）
        reset(orderMapper, stateMachine);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.updateRevokeAudit(ORDER_NO, String.valueOf(OPERATOR))).thenReturn(0);
        assertThatThrownBy(() -> service.revokeAudit(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("用例⑨重整不改状态：两条医嘱仅留痕（from=to+医嘱重整），零迁移零事件")
    void reorganizeKeepsStatusAndLeavesTraceOnly() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        MedicalOrder first = orderRow("DRUG", OrderStatus.EXECUTING);
        first.setOrderNo("MO1");
        MedicalOrder second = orderRow("LAB", OrderStatus.AUDITED);
        second.setOrderNo("MO2");
        when(orderMapper.selectOne(any())).thenReturn(first, second);

        service.reorganize(new OrderReorganizeRequest(VISIT_ID, List.of("MO1", "MO2")));

        // 状态面零变更（重整不触状态机）+ 事件零发布
        verifyNoInteractions(stateMachine, events);
        // 留痕：每条一行 from=to（无迁移动作）、reason=医嘱重整
        verify(statusLogMapper, times(2)).insert(statusLogCaptor.capture());
        List<OrderStatusLog> rows = statusLogCaptor.getAllValues();
        assertThat(rows)
                .extracting(OrderStatusLog::getFromStatus)
                .containsExactly(OrderStatus.EXECUTING.getCode(), OrderStatus.AUDITED.getCode());
        assertThat(rows.get(0).getToStatus()).isEqualTo(rows.get(0).getFromStatus());
        assertThat(rows.get(1).getToStatus()).isEqualTo(rows.get(1).getFromStatus());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getReason()).isEqualTo("医嘱重整");
            assertThat(row.getOperator()).isEqualTo(String.valueOf(OPERATOR));
            assertThat(row.getOccurredAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("重整守卫面：就诊不存在 IP-1007/医嘱不存在 IP-1009/归属其他就诊 IP-1023")
    void reorganizeCoversGuardFaces() {
        // 就诊不存在：IP-1007
        when(visitMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.reorganize(new OrderReorganizeRequest(VISIT_ID, List.of("MO1"))))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));

        // 医嘱不存在：IP-1009
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.reorganize(new OrderReorganizeRequest(VISIT_ID, List.of("MO404"))))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ORDER_NOT_FOUND));

        // 归属其他就诊（跨就诊医嘱号混入）：IP-1023
        MedicalOrder otherVisitOrder = orderRow("DRUG", OrderStatus.AUDITED);
        otherVisitOrder.setVisitId(9999L);
        when(orderMapper.selectOne(any())).thenReturn(otherVisitOrder);
        assertThatThrownBy(() -> service.reorganize(new OrderReorganizeRequest(VISIT_ID, List.of("MO1"))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("口头医嘱补录确认成功：oral 行存在→oral_confirmed_at 落值（操作者=上下文）")
    void oralConfirmWritesConfirmedAt() {
        MedicalOrder order = orderRow("DRUG", OrderStatus.EXECUTING);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(itemMapper.selectCount(any())).thenReturn(1L);
        when(orderMapper.updateOralConfirmedAt(eq(ORDER_NO), any(), eq(String.valueOf(OPERATOR))))
                .thenReturn(1);

        service.oralConfirm(ORDER_NO);

        verify(orderMapper).updateOralConfirmedAt(eq(ORDER_NO), any(), eq(String.valueOf(OPERATOR)));
    }

    @Test
    @DisplayName("口头医嘱补录确认守卫面：非口头医嘱 IP-1010/已确认 IP-1010/并发落写零行 IP-1023")
    void oralConfirmCoversGuardFaces() {
        // 非抢救口头医嘱（无 oral_flag 明细行）：IP-1010
        MedicalOrder order = orderRow("DRUG", OrderStatus.EXECUTING);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(itemMapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> service.oralConfirm(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ORDER_STATE_NOT_ALLOWED));

        // 已确认（oral_confirmed_at 非空）：IP-1010
        MedicalOrder confirmed = orderRow("DRUG", OrderStatus.EXECUTING);
        confirmed.setOralConfirmedAt(OffsetDateTime.now());
        when(orderMapper.selectOne(any())).thenReturn(confirmed);
        when(itemMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.oralConfirm(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ORDER_STATE_NOT_ALLOWED));

        // 落写零行（IS NULL 限定兜底并发双确认窗口）：IP-1023
        MedicalOrder pending = orderRow("DRUG", OrderStatus.EXECUTING);
        when(orderMapper.selectOne(any())).thenReturn(pending);
        when(orderMapper.updateOralConfirmedAt(eq(ORDER_NO), any(), eq(String.valueOf(OPERATOR))))
                .thenReturn(0);
        assertThatThrownBy(() -> service.oralConfirm(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("审核链守卫面：医嘱不存在 IP-1009/order_type 词表外脏数据 IP-1023/操作者非数字 IP-1022")
    void auditCoversGuardFaces() {
        // 医嘱不存在：IP-1009
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.audit(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ORDER_NOT_FOUND));

        // order_type 词表外脏数据（审核链无法裁决用药面）：IP-1023
        MedicalOrder dirty = orderRow("HERB", OrderStatus.CREATED);
        when(orderMapper.selectOne(any())).thenReturn(dirty);
        assertThatThrownBy(() -> service.audit(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));

        // 操作者非数字（无法定位审核操作主体）：IP-1022，文案不含原值；短标识全掩码分支
        // （词表内医嘱复位——操作者守卫在类型裁决之后，前置面须为合法类型）
        when(orderMapper.selectOne(any())).thenReturn(orderRow("LAB", OrderStatus.CREATED));
        OperatorContextHolder.set("doc-01");
        assertThatThrownBy(() -> service.audit(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getMessage()).doesNotContain("doc-01");
                });
        OperatorContextHolder.set("ab");
        assertThatThrownBy(() -> service.audit(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("ab"));
        OperatorContextHolder.set(String.valueOf(OPERATOR));
        verifyNoInteractions(stateMachine, events);
    }

    @Test
    @DisplayName("过审副作用守卫面：生效时点落写零行 IP-1023/事件号映射行缺失 IP-1007")
    void auditedSideEffectsCoverZeroRowAndMissingVisit() {
        // 生效时点落写零行（并发逻辑删窗口）：IP-1023，事件零发布
        MedicalOrder order = orderRow("LAB", OrderStatus.CREATED);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.updateAuditBegin(eq(ORDER_NO), any(), eq(String.valueOf(OPERATOR))))
                .thenReturn(0);
        assertThatThrownBy(() -> service.audit(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
        verifyNoInteractions(events);

        // 事件号映射行缺失（数据不一致）：IP-1007（事务整体回滚语义）
        reset(orderMapper, stateMachine);
        when(orderMapper.selectOne(any())).thenReturn(orderRow("LAB", OrderStatus.CREATED));
        when(orderMapper.updateAuditBegin(eq(ORDER_NO), any(), eq(String.valueOf(OPERATOR))))
                .thenReturn(1);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);
        assertThatThrownBy(() -> service.audit(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));
    }

    @Test
    @DisplayName("回执守卫面：医嘱不存在 IP-1009/词表外脏数据 IP-1023/操作者非数字与缺失容错 0 记录")
    void replyHandlersCoverGuardFaces() {
        // 医嘱不存在（回执定位失配，异常传播进死信留痕）：IP-1009
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.onPharmacistApproved(ORDER_NO, REVIEW_TASK_NO, "1002", REPLY_AT))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ORDER_NOT_FOUND));

        // 词表外脏数据：IP-1023
        MedicalOrder dirty = orderRow("HERB", OrderStatus.CREATED);
        when(orderMapper.selectOne(any())).thenReturn(dirty);
        assertThatThrownBy(() -> service.onPharmacistApproved(ORDER_NO, REVIEW_TASK_NO, "1002", REPLY_AT))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));

        // 回执操作者非数字（容错 0 记录——回执结论不因标识异常丢失）：迁移操作者=0
        MedicalOrder order = orderRow("DRUG", OrderStatus.CREATED);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.updateAuditBegin(eq(ORDER_NO), any(), eq("0"))).thenReturn(1);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        service.onPharmacistApproved(ORDER_NO, REVIEW_TASK_NO, "pharm-07", REPLY_AT);
        verify(stateMachine).transition(order, OrderStatus.AUDITED, "药师审方通过", 0L);

        // 回执操作者缺失（null 同款容错 0）：驳回迁移操作者=0
        MedicalOrder another = orderRow("DRUG", OrderStatus.CREATED);
        when(orderMapper.selectOne(any())).thenReturn(another);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        service.onPharmacistRejected(ORDER_NO, REVIEW_TASK_NO, "剂量超限", null, REPLY_AT);
        verify(stateMachine).transition(another, OrderStatus.AUDIT_REJECTED, "剂量超限", 0L);
    }

    @Test
    @DisplayName("GC26 可执行锚：审核/撤回/重提/口头确认四值面均为 @Update 注解 SQL 且显式 deleted=0")
    void valueFaceUpdatesAreAnnotatedSqlWithDeletedGuard() throws NoSuchMethodException {
        Update begin = MedicalOrderMapper.class
                .getMethod("updateAuditBegin", String.class, OffsetDateTime.class, String.class)
                .getAnnotation(Update.class);
        assertThat(begin).as("生效时点落值必须为 @Update 注解 SQL（GC26）").isNotNull();
        assertThat(String.join("", begin.value()))
                .contains("SET begin_at = #{beginAt}")
                .contains("deleted = 0");

        Update revoke = MedicalOrderMapper.class
                .getMethod("updateRevokeAudit", String.class, String.class)
                .getAnnotation(Update.class);
        assertThat(String.join("", revoke.value())).contains("begin_at = NULL").contains("deleted = 0");

        Update resubmit = MedicalOrderMapper.class
                .getMethod(
                        "updateResubmitValues",
                        String.class,
                        String.class,
                        String.class,
                        boolean.class,
                        String.class,
                        String.class)
                .getAnnotation(Update.class);
        assertThat(String.join("", resubmit.value()))
                .contains("order_type = #{orderType}")
                .contains("freq_code = #{freqCode}")
                .contains("deleted = 0");

        Update oral = MedicalOrderMapper.class
                .getMethod("updateOralConfirmedAt", String.class, OffsetDateTime.class, String.class)
                .getAnnotation(Update.class);
        assertThat(String.join("", oral.value()))
                .contains("oral_confirmed_at IS NULL")
                .contains("deleted = 0");
    }

    /** 构造在院就诊行（事件载荷 visitId 号映射载体）。 */
    private InpatientVisit visitRow() {
        InpatientVisit row = new InpatientVisit();
        row.setId(VISIT_PK);
        row.setVisitId(VISIT_ID);
        row.setPatientId(PATIENT_ID);
        row.setStatus(VisitStatus.ADMITTED.getCode());
        return row;
    }

    /** 构造指定类型与状态的医嘱行（审核链裁决载体）。 */
    private MedicalOrder orderRow(String orderType, OrderStatus status) {
        MedicalOrder row = new MedicalOrder();
        row.setId(9001L);
        row.setOrderNo(ORDER_NO);
        row.setVisitId(VISIT_PK);
        row.setPatientId(PATIENT_ID);
        row.setOrderType(orderType);
        row.setOrderClass("LONG");
        row.setGroupNo(ORDER_NO);
        row.setDoctorId(String.valueOf(OPERATOR));
        row.setStatus(status.getCode());
        return row;
    }
}
