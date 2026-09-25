package com.fuyun.inpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.entity.MedicalOrder;
import com.fuyun.inpatient.entity.OrderStatusLog;
import com.fuyun.inpatient.enums.OrderStatus;
import com.fuyun.inpatient.mapper.MedicalOrderMapper;
import com.fuyun.inpatient.mapper.OrderStatusLogMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 医嘱状态机单测（Task 5 冻结集 + Task 6 留痕回接面）：04 Spec §3.3 合法迁移表全集 13 条边
 * 逐一放行（brief 冻结「合法八条全过」——本套以 13 边参数化全覆盖超集承载）+ 非法迁移两条拒
 * （COMPLETED→EXECUTING、CREATED→TRANSFERRED，IP-1010）+ CAS 零行并发窗口拒 + GC26 可执行锚
 * （状态 CAS 注解 SQL 限定 from 态与 deleted=0）。Task 6 回接后追加留痕断言：每次迁移
 * order_status_log 只增落一行（from/to/reason/operator 载荷与迁移实况一致）。
 */
@ExtendWith(MockitoExtension.class)
class OrderStateMachineServiceImplTest {

    /** 操作者员工 ID（执业授权/审计口径） */
    private static final long OPERATOR = 1001L;

    @Mock
    private MedicalOrderMapper orderMapper;

    @Mock
    private OrderStatusLogMapper statusLogMapper;

    @Captor
    private ArgumentCaptor<OrderStatusLog> statusLogCaptor;

    private OrderStateMachineServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderStateMachineServiceImpl(orderMapper, statusLogMapper);
    }

    /** 合法迁移边全集（04 Spec §3.3：主链+侧支+终态收口，13 条）。 */
    static List<Map.Entry<OrderStatus, OrderStatus>> legalEdges() {
        return List.of(
                Map.entry(OrderStatus.CREATED, OrderStatus.AUDITED),
                Map.entry(OrderStatus.CREATED, OrderStatus.AUDIT_REJECTED),
                Map.entry(OrderStatus.AUDIT_REJECTED, OrderStatus.CREATED),
                Map.entry(OrderStatus.AUDITED, OrderStatus.CREATED),
                Map.entry(OrderStatus.AUDITED, OrderStatus.TRANSFERRED),
                Map.entry(OrderStatus.AUDITED, OrderStatus.CANCELLED),
                Map.entry(OrderStatus.AUDITED, OrderStatus.STOPPED),
                Map.entry(OrderStatus.TRANSFERRED, OrderStatus.CANCELLED),
                Map.entry(OrderStatus.TRANSFERRED, OrderStatus.EXECUTING),
                Map.entry(OrderStatus.TRANSFERRED, OrderStatus.COMPLETED),
                Map.entry(OrderStatus.TRANSFERRED, OrderStatus.STOPPED),
                Map.entry(OrderStatus.EXECUTING, OrderStatus.COMPLETED),
                Map.entry(OrderStatus.EXECUTING, OrderStatus.STOPPED));
    }

    @ParameterizedTest(name = "合法迁移第 {index} 边：{0}→{1}")
    @MethodSource("legalEdges")
    @DisplayName("合法迁移表全集 13 边逐一放行：CAS from 态限定 + 内存行同步目标态 + order_status_log 留痕落库")
    void legalTransitionRunsCasAndSyncsMemoryRow(Map.Entry<OrderStatus, OrderStatus> edge) {
        MedicalOrder order = orderRow(edge.getKey());
        when(orderMapper.casTransferStatus(
                        "MO1", edge.getKey().getCode(), edge.getValue().getCode(), "1001"))
                .thenReturn(1);

        service.transition(order, edge.getValue(), "审核通过", OPERATOR);

        // CAS 唯一执行面：from 态限定更新至 to 态（并发迁移互斥锚）
        verify(orderMapper)
                .casTransferStatus(
                        "MO1", edge.getKey().getCode(), edge.getValue().getCode(), "1001");
        // 内存行同步目标态（调用方值面补写/事件发布免回读）
        assertThat(order.getStatus()).isEqualTo(edge.getValue().getCode());
        // 迁移留痕（Task 6 回接面）：order_status_log 只增落一行，from/to/reason/operator 全息
        verify(statusLogMapper).insert(statusLogCaptor.capture());
        OrderStatusLog logRow = statusLogCaptor.getValue();
        assertThat(logRow.getOrderId()).isEqualTo(order.getId());
        assertThat(logRow.getFromStatus()).isEqualTo(edge.getKey().getCode());
        assertThat(logRow.getToStatus()).isEqualTo(edge.getValue().getCode());
        assertThat(logRow.getReason()).isEqualTo("审核通过");
        assertThat(logRow.getOperator()).isEqualTo("1001");
        assertThat(logRow.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("非法迁移拒绝（一）：COMPLETED→EXECUTING 终态再迁移，IP-1010 且 CAS 零触达")
    void rejectsTerminalToExecuting() {
        MedicalOrder order = orderRow(OrderStatus.COMPLETED);

        assertThatThrownBy(() -> service.transition(order, OrderStatus.EXECUTING, "重开执行", OPERATOR))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ORDER_STATE_NOT_ALLOWED);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(orderMapper);
    }

    @Test
    @DisplayName("非法迁移拒绝（二）：CREATED→TRANSFERRED 跳过审核直转抄（迁移表外路径），IP-1010 且 CAS 零触达")
    void rejectsCreatedToTransferred() {
        MedicalOrder order = orderRow(OrderStatus.CREATED);

        assertThatThrownBy(() -> service.transition(order, OrderStatus.TRANSFERRED, "直转抄", OPERATOR))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ORDER_STATE_NOT_ALLOWED));
        verifyNoInteractions(orderMapper);
    }

    @Test
    @DisplayName("词表外 status（主数据脏数据）同拒 IP-1010——fail-closed，不触 CAS")
    void rejectsUnknownStatusCode() {
        MedicalOrder order = orderRow(OrderStatus.CREATED);
        order.setStatus("PAUSED");

        assertThatThrownBy(() -> service.transition(order, OrderStatus.AUDITED, "审核通过", OPERATOR))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ORDER_STATE_NOT_ALLOWED));
        verifyNoInteractions(orderMapper);
    }

    @Test
    @DisplayName("CAS 零行（并发迁移窗口 he 方先迁）：IP-1010 定性冲突，内存行不置目标态")
    void rejectsConcurrentCasMiss() {
        MedicalOrder order = orderRow(OrderStatus.AUDITED);
        when(orderMapper.casTransferStatus(eq("MO1"), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.transition(order, OrderStatus.STOPPED, "转科", OPERATOR))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ORDER_STATE_NOT_ALLOWED));
        // 内存行保持迁移前实态（快照失效——调用方须回读后重试）
        assertThat(order.getStatus()).isEqualTo(OrderStatus.AUDITED.getCode());
        // CAS 零行未迁移：留痕零落库（迁移失败不写 order_status_log）
        verify(statusLogMapper, never()).insert(any(OrderStatusLog.class));
    }

    @Test
    @DisplayName("GC26 可执行锚：状态 CAS 为 @Update 注解 SQL，from 态限定 + 显式 deleted=0")
    void casSqlCarriesFromStateGuardAndDeletedFilter() throws NoSuchMethodException {
        Update update = MedicalOrderMapper.class
                .getMethod("casTransferStatus", String.class, String.class, String.class, String.class)
                .getAnnotation(Update.class);
        assertThat(update).as("条件更新必须为 @Update 注解 SQL（GC26）").isNotNull();
        String sql = String.join("", update.value());
        assertThat(sql)
                .contains("SET status = #{toState}, updated_by = #{operator}")
                .contains("WHERE order_no = #{orderNo}")
                .contains("status = #{fromState}")
                .contains("deleted = 0");
    }

    /** 构造指定状态医嘱行（迁移裁决载体）。 */
    private MedicalOrder orderRow(OrderStatus status) {
        MedicalOrder row = new MedicalOrder();
        row.setId(9001L);
        row.setOrderNo("MO1");
        row.setVisitId(7001L);
        row.setPatientId(1001L);
        row.setOrderClass("LONG");
        row.setStatus(status.getCode());
        return row;
    }
}
