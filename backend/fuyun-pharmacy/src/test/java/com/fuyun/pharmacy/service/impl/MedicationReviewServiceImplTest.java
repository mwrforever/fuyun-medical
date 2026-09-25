package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.api.MedicationAuditCompletedPayload;
import com.fuyun.pharmacy.api.MedicationAuditRejectedPayload;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.entity.OrderMedication;
import com.fuyun.pharmacy.entity.ReviewTask;
import com.fuyun.pharmacy.internal.PharmacyDomainEvent;
import com.fuyun.pharmacy.mapper.OrderMedicationMapper;
import com.fuyun.pharmacy.mapper.ReviewTaskMapper;
import com.fuyun.pharmacy.vo.ReviewTaskVO;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 住院用药审方服务单测（P2 PR-1 Task 12，brief 冻结六用例承载面）：消费落两表/重复消费幂等/
 * 重提重开 + 通过驳回回执载荷 target 断言/驳回缺意见 PH-1020/PENDING 才可决状态机。GC24 单测
 * 构造范式：MockitoExtension + TableInfoHelper 表信息注册 + 构造器注入 collaborator mock。
 */
@ExtendWith(MockitoExtension.class)
class MedicationReviewServiceImplTest {

    private static final String ITEMS_JSON = """
            [{"itemSeq":1,"itemCode":"D-IT-001","itemName":"头孢呋辛酯片","dosage":"0.5g","unit":"g","route":"口服","quantity":"12","itemType":"DRUG"}]
            """;
    private static final String ITEMS_JSON_V2 = """
            [{"itemSeq":1,"itemCode":"D-IT-002","itemName":"阿莫西林胶囊","dosage":"0.25g","unit":"g","route":"口服","quantity":"24","itemType":"DRUG"}]
            """;

    @Mock
    private OrderMedicationMapper medicationMapper;

    @Mock
    private ReviewTaskMapper taskMapper;

    @Mock
    private ApplicationEventPublisher events;

    private MedicationReviewServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // GC24 范式：lambda wrapper 依赖的 MP 表信息注册（entity 两表）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderMedication.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTask.class);
    }

    @BeforeEach
    void setUp() {
        service = new MedicationReviewServiceImpl(medicationMapper, taskMapper, events);
        OperatorContextHolder.set("301");
    }

    @AfterEach
    void tearDown() {
        OperatorContextHolder.clear();
    }

    /** 快照行构造（uk 定位键 m04OrderNo 唯一） */
    private OrderMedication medication(long id, String m04OrderNo) {
        OrderMedication med = new OrderMedication();
        med.setId(id);
        med.setM04OrderNo(m04OrderNo);
        med.setVisitId("I2026092500001");
        med.setPatientId(700101L);
        med.setFreqCode("qd");
        med.setItems(ITEMS_JSON);
        return med;
    }

    /** 任务行构造（orderMedicationId 关联快照；status 与决策留痕按用例赋值） */
    private ReviewTask task(long id, long medicationId, String status) {
        ReviewTask t = new ReviewTask();
        t.setId(id);
        t.setOrderMedicationId(medicationId);
        t.setStatus(status);
        return t;
    }

    @Test
    @DisplayName("① drug 子键消费落两表：快照五值 + PENDING 任务关联快照主键（首投路径）")
    void consumedEventPersistsSnapshotAndPendingTask() {
        // MP ASSIGN_ID 插入期回填的 mock 等价形态：插入 Answer 回填主键
        when(medicationMapper.insert(any(OrderMedication.class))).thenAnswer(inv -> {
            inv.getArgument(0, OrderMedication.class).setId(9001L);
            return 1;
        });
        when(taskMapper.insert(any(ReviewTask.class))).thenReturn(1);
        when(medicationMapper.selectOne(any())).thenReturn(null);

        service.onOrderCreated("M20260925001", "I2026092500001", 700101L, "qd", ITEMS_JSON);

        ArgumentCaptor<OrderMedication> medCaptor = ArgumentCaptor.forClass(OrderMedication.class);
        verify(medicationMapper).insert(medCaptor.capture());
        OrderMedication med = medCaptor.getValue();
        assertThat(med.getM04OrderNo()).isEqualTo("M20260925001");
        assertThat(med.getVisitId()).isEqualTo("I2026092500001");
        assertThat(med.getPatientId()).isEqualTo(700101L);
        assertThat(med.getFreqCode()).isEqualTo("qd");
        assertThat(med.getItems()).isEqualTo(ITEMS_JSON);
        ArgumentCaptor<ReviewTask> taskCaptor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(taskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getOrderMedicationId()).isEqualTo(9001L);
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("PENDING");
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("⑤ 重复消费幂等：同医嘱号事件再投且任务在审（PENDING）仅收敛不新建不复位")
    void duplicateRepublishSkipsWhenTaskPending() {
        OrderMedication existing = medication(9001L, "M20260925001");
        when(medicationMapper.selectOne(any())).thenReturn(existing);
        when(taskMapper.selectOne(any())).thenReturn(task(9002L, 9001L, "PENDING"));

        service.onOrderCreated("M20260925001", "I2026092500001", 700101L, "qd", ITEMS_JSON_V2);

        verify(medicationMapper, never()).insert(any(OrderMedication.class));
        verify(taskMapper, never()).insert(any(ReviewTask.class));
        verify(taskMapper, never()).casReopen(anyLong());
        verify(medicationMapper, never()).refreshResubmitted(anyLong(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("⑤补 重提闭环：REJECTED 任务重发事件同步头值频次+明细快照刷新 + 同任务复位 PENDING（重开非新建）")
    void rejectedRepublishReopensSameTaskWithFreshSnapshot() {
        OrderMedication existing = medication(9001L, "M20260925001");
        when(medicationMapper.selectOne(any())).thenReturn(existing);
        when(taskMapper.selectOne(any())).thenReturn(task(9002L, 9001L, "REJECTED"));
        when(taskMapper.casReopen(9002L)).thenReturn(1);

        // M04 resubmit 头值面可改频次：快照原频次 qd，重提事件携新频次 bid
        service.onOrderCreated("M20260925001", "I2026092500001", 700101L, "bid", ITEMS_JSON_V2);

        // 重提可改方：头值频次与明细快照同语句刷新为新事件面；任务行复位（decided_at/opinion/pharmacist_id 由 CAS 语句清空）
        verify(medicationMapper).refreshResubmitted(9001L, "bid", ITEMS_JSON_V2);
        verify(taskMapper).casReopen(9002L);
        verify(medicationMapper, never()).insert(any(OrderMedication.class));
        verify(taskMapper, never()).insert(any(ReviewTask.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("③ 审方通过回执：audit-completed 发布 target=m04 医嘱号 + auditNo=任务 id（V800 id 53 冻结组件）")
    void approvePublishesCompletedReplyWithTargetAssertion() {
        when(taskMapper.selectById(5001L)).thenReturn(task(5001L, 9001L, "PENDING"));
        when(medicationMapper.selectById(9001L)).thenReturn(medication(9001L, "M20260925001"));
        when(taskMapper.casDecide(eq(5001L), eq("APPROVED"), eq("301"), isNull(), any()))
                .thenReturn(1);

        service.approve(5001L, null);

        ArgumentCaptor<PharmacyDomainEvent> captor = ArgumentCaptor.forClass(PharmacyDomainEvent.class);
        verify(events).publishEvent(captor.capture());
        PharmacyDomainEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("pharmacy.medication-order.audit-completed");
        MedicationAuditCompletedPayload payload = (MedicationAuditCompletedPayload) event.payload();
        assertThat(payload.target()).isEqualTo("M20260925001");
        assertThat(payload.auditNo()).isEqualTo("5001");
        assertThat(payload.auditOperator()).isEqualTo("301");
        assertThat(payload.auditedAt()).isNotNull();
        verify(taskMapper).casDecide(eq(5001L), eq("APPROVED"), eq("301"), isNull(), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("⑤补 重开并发被抢：casReopen 0 行 warn 收敛不上抛（快照刷新保留、零事件）")
    void rejectedRepublishRaceLosesReopenConvergesQuietly() {
        OrderMedication existing = medication(9001L, "M20260925001");
        when(medicationMapper.selectOne(any())).thenReturn(existing);
        when(taskMapper.selectOne(any())).thenReturn(task(9002L, 9001L, "REJECTED"));
        // 并发窗口：他方决策抢先完成迁移，复位 0 行
        when(taskMapper.casReopen(9002L)).thenReturn(0);

        service.onOrderCreated("M20260925001", "I2026092500001", 700101L, "qd", ITEMS_JSON_V2);

        verify(medicationMapper).refreshResubmitted(9001L, "qd", ITEMS_JSON_V2);
        verify(taskMapper).casReopen(9002L);
        verify(medicationMapper, never()).insert(any(OrderMedication.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("⑤补 任务行缺失（uk+同事务原子性兜底的理论不可达面）：防御跳过不重建不误开")
    void republishWithMissingTaskRowSkipsDefensively() {
        when(medicationMapper.selectOne(any())).thenReturn(medication(9001L, "M20260925001"));
        when(taskMapper.selectOne(any())).thenReturn(null);

        service.onOrderCreated("M20260925001", "I2026092500001", 700101L, "qd", ITEMS_JSON);

        verify(medicationMapper, never()).insert(any(OrderMedication.class));
        verify(taskMapper, never()).insert(any(ReviewTask.class));
        verify(taskMapper, never()).casReopen(anyLong());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("③补 通过携空白意见归一 NULL（空白不入库，保持列 NULL 语义）")
    void approveBlankOpinionNormalizedToNull() {
        when(taskMapper.selectById(5001L)).thenReturn(task(5001L, 9001L, "PENDING"));
        when(medicationMapper.selectById(9001L)).thenReturn(medication(9001L, "M20260925001"));
        when(taskMapper.casDecide(eq(5001L), eq("APPROVED"), eq("301"), isNull(), any()))
                .thenReturn(1);

        service.approve(5001L, "  ");

        verify(taskMapper).casDecide(eq(5001L), eq("APPROVED"), eq("301"), isNull(), any(OffsetDateTime.class));
        verify(events).publishEvent(any(PharmacyDomainEvent.class));
    }

    @Test
    @DisplayName("④ 审方驳回回执：audit-rejected 发布 rejectReason=药师意见必附（V800 id 54 冻结组件）")
    void rejectPublishesRejectedReplyWithRejectReason() {
        when(taskMapper.selectById(5001L)).thenReturn(task(5001L, 9001L, "PENDING"));
        when(medicationMapper.selectById(9001L)).thenReturn(medication(9001L, "M20260925001"));
        when(taskMapper.casDecide(eq(5001L), eq("REJECTED"), eq("301"), eq("剂量超限，请核对"), any()))
                .thenReturn(1);

        service.reject(5001L, "剂量超限，请核对");

        ArgumentCaptor<PharmacyDomainEvent> captor = ArgumentCaptor.forClass(PharmacyDomainEvent.class);
        verify(events).publishEvent(captor.capture());
        PharmacyDomainEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("pharmacy.medication-order.audit-rejected");
        MedicationAuditRejectedPayload payload = (MedicationAuditRejectedPayload) event.payload();
        assertThat(payload.target()).isEqualTo("M20260925001");
        assertThat(payload.rejectReason()).isEqualTo("剂量超限，请核对");
        assertThat(payload.auditNo()).isEqualTo("5001");
        assertThat(payload.auditOperator()).isEqualTo("301");
        assertThat(payload.auditedAt()).isNotNull();
    }

    @Test
    @DisplayName("④ 驳回缺意见拒 PH-1020（null/空白双面——brief 冻结语义，不 400 先拦）")
    void rejectWithoutOpinionRejectedPh1020() {
        assertThatThrownBy(() -> service.reject(5001L, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.REVIEW_TASK_STATE_NOT_ALLOWED));
        assertThatThrownBy(() -> service.reject(5001L, "  "))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.REVIEW_TASK_STATE_NOT_ALLOWED));
        // 守卫前置即拒：零查询零 CAS 零事件
        verifyNoInteractions(taskMapper, medicationMapper, events);
    }

    @Test
    @DisplayName("⑥ 状态机：CAS 0 行拒 PH-1020（PENDING 唯一可决出边——APPROVED 再决/并发被抢同拒）")
    void decideNonPendingTaskRejectedPh1020() {
        when(taskMapper.selectById(5001L)).thenReturn(task(5001L, 9001L, "APPROVED"));
        when(medicationMapper.selectById(9001L)).thenReturn(medication(9001L, "M20260925001"));
        when(taskMapper.casDecide(eq(5001L), eq("APPROVED"), eq("301"), isNull(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.approve(5001L, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.REVIEW_TASK_STATE_NOT_ALLOWED));
        verifyNoInteractions(events);

        when(taskMapper.casDecide(eq(5001L), eq("REJECTED"), eq("301"), anyString(), any()))
                .thenReturn(0);
        assertThatThrownBy(() -> service.reject(5001L, "剂量超限，请核对"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.REVIEW_TASK_STATE_NOT_ALLOWED));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("守卫 任务不存在拒 PH-1019（404）/关联快照缺失拒 PH-1021（404 数据不一致面）")
    void decisionGuardsReturnNotFound() {
        when(taskMapper.selectById(5001L)).thenReturn(null);
        assertThatThrownBy(() -> service.approve(5001L, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.REVIEW_TASK_NOT_FOUND));

        when(taskMapper.selectById(5001L)).thenReturn(task(5001L, 9001L, "PENDING"));
        when(medicationMapper.selectById(9001L)).thenReturn(null);
        assertThatThrownBy(() -> service.reject(5001L, "剂量超限，请核对"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.MEDICATION_ORDER_NOT_FOUND));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("工作台列表：任务行联装快照号面/明细回显 + PageResult 形态（空页短路零联装）")
    void listReturnsWorkbenchPage() {
        ReviewTask pending = task(5001L, 9001L, "PENDING");
        Page<ReviewTask> page = new Page<>(1, 10);
        page.setRecords(List.of(pending));
        page.setTotal(1);
        when(taskMapper.selectPage(any(), any())).thenReturn(page);
        when(medicationMapper.selectByIds(any())).thenReturn(List.of(medication(9001L, "M20260925001")));

        PageResult<ReviewTaskVO> result = service.list("PENDING", 0, 10);

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.total()).isEqualTo(1);
        ReviewTaskVO vo = result.content().get(0);
        assertThat(vo.id()).isEqualTo(5001L);
        assertThat(vo.m04OrderNo()).isEqualTo("M20260925001");
        assertThat(vo.visitId()).isEqualTo("I2026092500001");
        assertThat(vo.patientId()).isEqualTo(700101L);
        assertThat(vo.items()).isEqualTo(ITEMS_JSON);
        assertThat(vo.status()).isEqualTo("PENDING");

        // 空页短路：零联装直出空清单（禁单行 null 出网）
        Page<ReviewTask> empty = new Page<>(1, 10);
        empty.setRecords(List.of());
        when(taskMapper.selectPage(any(), any())).thenReturn(empty);
        assertThat(service.list(null, 0, 10).content()).isEmpty();
    }

    @Test
    @DisplayName("列表谓词锚：status 非空等值过滤 + id 升序 FIFO；空白不过滤全量")
    void buildListWrapperFiltersStatusAndOrdersFifo() {
        Wrapper<ReviewTask> filtered = service.buildListWrapper("PENDING");
        String filteredSql = ((AbstractWrapper<?, ?, ?>) filtered).getSqlSegment();
        assertThat(filteredSql).contains("status").contains("ORDER BY").contains("id");

        Wrapper<ReviewTask> unfiltered = service.buildListWrapper(" ");
        String unfilteredSql = ((AbstractWrapper<?, ?, ?>) unfiltered).getSqlSegment();
        assertThat(unfilteredSql).doesNotContain("status").contains("ORDER BY").contains("id");
    }
}
