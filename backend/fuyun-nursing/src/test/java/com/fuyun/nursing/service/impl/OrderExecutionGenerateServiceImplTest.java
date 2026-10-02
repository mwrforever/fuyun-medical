package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.entity.OrderExecution;
import com.fuyun.nursing.enums.ExecutionStatus;
import com.fuyun.nursing.enums.ExecutionType;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.mapper.OrderExecutionMapper;
import com.fuyun.nursing.service.IOrderExecutionService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.annotations.Insert;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 执行单生成域单测（Task 4 brief 冻结用例组①–⑦ + 补充覆盖锚）：临时单生成含类型快照、
 * 三类型过滤零落单、计划批量 12 单、重复 planNo 幂等零副作用（uk_execution_plan
 * ON CONFLICT DO NOTHING 形态）、停嘱/作废撤销仅未执行态、乱序防御（计划先于转抄到达）。
 * MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息；幂等插入与 CAS
 * 断言直读注解 SQL（GC26 可执行锚）。消费链路无登录上下文：审计列断言按 system 回退口径。
 */
@ExtendWith(MockitoExtension.class)
class OrderExecutionGenerateServiceImplTest {

    /** I 型 14 位合法 visit_id（执行单所属就诊） */
    private static final String VISIT = "I2026100200001";

    /** M04 医嘱号（生成来源医嘱引用） */
    private static final String ORDER_NO = "M20261002001";

    /** 病区编码（投影行回填 ward_id 断言基准） */
    private static final String WARD = "W01";

    /** 床位号（投影行回填 bed_no 断言基准） */
    private static final String BED = "03";

    /** 转抄时点（临时单 plan_time 断言基准，UTC 语义 Instant） */
    private static final Instant TRANSFERRED_AT = Instant.parse("2026-10-02T01:30:00Z");

    @Mock
    private OrderExecutionMapper executionMapper;

    @Mock
    private NursingSeqGate seqGate;

    @Mock
    private NursingWardPatientMapper wardPatientMapper;

    @Captor
    private ArgumentCaptor<OrderExecution> rowCaptor;

    private OrderExecutionGenerateServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（执行单主表 + 投影表）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderExecution.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingWardPatient.class);
    }

    @BeforeEach
    void setUp() {
        service = new OrderExecutionGenerateServiceImpl(executionMapper, seqGate, wardPatientMapper);
        ReflectionTestUtils.setField(service, "baseMapper", executionMapper);
        // 链式 lambdaQuery（A.4.3-13）走 getEntityClass（经 mapper 代理元数据解析），mock 下须显式注入
        ReflectionTestUtils.setField(service, "entityClass", OrderExecution.class);
    }

    @AfterEach
    void clearOperator() {
        // 消费链路本无登录上下文：防御性清理，防其他用例泄漏操作者串号到审计列断言
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("① 临时单生成：类型快照行落库（execItem=医嘱号占位/类型快照，GENERIC、CREATED、投影回填 ward/bed）")
    void onOrderTransferredCreatesSnapshotRowWithPlaceholderItem() {
        when(wardPatientMapper.selectOne(any())).thenReturn(projection());
        when(seqGate.nextNo("EX")).thenReturn("EX2026100200001");
        when(executionMapper.insertIgnorePlanConflict(any(OrderExecution.class)))
                .thenReturn(1);

        service.onOrderTransferred(ORDER_NO, VISIT, 7L, "drug", TRANSFERRED_AT);

        verify(executionMapper).insertIgnorePlanConflict(rowCaptor.capture());
        OrderExecution row = rowCaptor.getValue();
        // 类型快照契约：itemCode/itemName 为转抄占位（m04 单号+类型），明细核对时从 dispense 行回填
        assertThat(row.getExecItemCode()).isEqualTo(ORDER_NO);
        assertThat(row.getExecItemName()).isEqualTo("drug");
        assertThat(row.getM04OrderNo()).isEqualTo(ORDER_NO);
        // 临时单无计划号（m04_plan_no NULL——uk_execution_plan 对 NULL 互异不受约束）
        assertThat(row.getM04PlanNo()).isNull();
        // 生成默认面：GENERIC（INFUSION 升格归 dispense 回填时点）+ CREATED + 投影回填归属
        assertThat(row.getExecutionType()).isEqualTo(ExecutionType.GENERIC.getCode());
        assertThat(row.getStatus()).isEqualTo(ExecutionStatus.CREATED.getCode());
        assertThat(row.getWardId()).isEqualTo(WARD);
        assertThat(row.getBedNo()).isEqualTo(BED);
        assertThat(row.getVisitId()).isEqualTo(VISIT);
        assertThat(row.getPatientId()).isEqualTo(7L);
        assertThat(row.getExecutionNo()).isEqualTo("EX2026100200001");
        // 计划时点=转抄时点（北京钟面承载）；审计列按消费链路 system 回退
        assertThat(row.getPlanTime())
                .isEqualTo(TRANSFERRED_AT.atZone(TimeConstants.HEALTHCARE_TZ).toOffsetDateTime());
        assertThat(row.getCreatedBy()).isEqualTo("system");
        assertThat(row.getUpdatedBy()).isEqualTo("system");
    }

    @Test
    @DisplayName("② 类型过滤：blood/surgery/exam 三类转抄零落单（不触投影查询、不取号、不写行）")
    void onOrderTransferredFiltersBloodSurgeryExamTypes() {
        service.onOrderTransferred(ORDER_NO, VISIT, 7L, "blood", TRANSFERRED_AT);
        service.onOrderTransferred(ORDER_NO, VISIT, 7L, "surgery", TRANSFERRED_AT);
        service.onOrderTransferred(ORDER_NO, VISIT, 7L, "exam", TRANSFERRED_AT);

        verify(executionMapper, never()).insertIgnorePlanConflict(any(OrderExecution.class));
        verify(wardPatientMapper, never()).selectOne(any());
        verifyNoInteractions(seqGate);
    }

    @Test
    @DisplayName("③ 计划批量生成：12 个 planNo 逐单落库（planTime=计划日+时点北京钟面，快照占位字段继承）")
    void onPlanGeneratedCreatesExecutionPerPlanNo() {
        when(executionMapper.selectOne(any())).thenReturn(snapshotRow("drug"));
        when(wardPatientMapper.selectOne(any())).thenReturn(projection());
        AtomicInteger seq = new AtomicInteger();
        when(seqGate.nextNo("EX")).thenAnswer(inv -> "EX20261003" + String.format("%05d", seq.incrementAndGet()));
        when(executionMapper.insertIgnorePlanConflict(any(OrderExecution.class)))
                .thenReturn(1);
        LocalDate planDate = LocalDate.of(2026, 10, 3);
        List<String> planNos = planNos(12);
        List<String> planTimes = planTimes(12);

        service.onPlanGenerated(ORDER_NO, VISIT, 7L, planDate, planNos, planTimes);

        verify(executionMapper, times(12)).insertIgnorePlanConflict(rowCaptor.capture());
        List<OrderExecution> rows = rowCaptor.getAllValues();
        for (int i = 0; i < 12; i++) {
            OrderExecution row = rows.get(i);
            // 逐计划一执行单：m04_plan_no 与 planNo 逐位对齐，计划时点=计划日+HH:mm 北京钟面组合
            assertThat(row.getM04PlanNo()).as("planNo 下标 %s", i).isEqualTo(planNos.get(i));
            assertThat(row.getPlanTime())
                    .as("planTime 下标 %s", i)
                    .isEqualTo(planDate.atTime(LocalTime.parse(planTimes.get(i)))
                            .atZone(TimeConstants.HEALTHCARE_TZ)
                            .toOffsetDateTime());
            // 快照占位字段继承转抄快照行（itemCode=医嘱号占位/itemName=类型快照）
            assertThat(row.getExecItemCode()).isEqualTo(ORDER_NO);
            assertThat(row.getExecItemName()).isEqualTo("drug");
            assertThat(row.getStatus()).isEqualTo(ExecutionStatus.CREATED.getCode());
            assertThat(row.getExecutionType()).isEqualTo(ExecutionType.GENERIC.getCode());
        }
        // 发号 12 次（每单一号），执行单号互异
        assertThat(rows).extracting(OrderExecution::getExecutionNo).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("④ 重复 planNo 幂等：uk_execution_plan 冲突 ON CONFLICT DO NOTHING 零副作用（不抛异常零重写）")
    void onPlanGeneratedIsIdempotentOnDuplicatePlanNo() {
        when(executionMapper.selectOne(any())).thenReturn(snapshotRow("drug"));
        when(wardPatientMapper.selectOne(any())).thenReturn(projection());
        when(seqGate.nextNo("EX")).thenReturn("EX2026100300001");
        // 全量重放场景：库内已有同 (m04_order_no, m04_plan_no) 行 → 插入 0 行（ON CONFLICT DO NOTHING）
        when(executionMapper.insertIgnorePlanConflict(any(OrderExecution.class)))
                .thenReturn(0);

        assertThatCode(() -> service.onPlanGenerated(
                        ORDER_NO, VISIT, 7L, LocalDate.of(2026, 10, 3), planNos(12), planTimes(12)))
                .doesNotThrowAnyException();

        // 零副作用锚：12 单全部尝试插入且全部被唯一索引吞掉，无异常上抛（重复事件消费成功确认）
        verify(executionMapper, times(12)).insertIgnorePlanConflict(any(OrderExecution.class));
        // GC26 可执行锚：幂等插入必须为 ON CONFLICT DO NOTHING 注解 SQL（uk_execution_plan 部分索引谓词）
        assertThat(insertSql()).contains("ON CONFLICT (m04_order_no, m04_plan_no) WHERE deleted = 0 DO NOTHING");
    }

    @Test
    @DisplayName("⑤ 停嘱撤销：仅未执行态（CREATED/SIGNED/CHECKED）批量 CANCELLED 并落原因，EXECUTING 不动")
    void onOrderTerminalStoppedCancelsOnlyUnexecutedRows() {
        when(executionMapper.casCancelBatch(ORDER_NO, "患者病情好转", "system")).thenReturn(3);

        service.onOrderTerminal(ORDER_NO, "患者病情好转", IOrderExecutionService.TerminalKind.STOPPED);

        // 数据库写操作：撤销 CAS（原因留痕 + 操作者审计），影响行数仅日志口径不构成失败
        verify(executionMapper).casCancelBatch(ORDER_NO, "患者病情好转", "system");
        // GC26 可执行锚：撤销必须为 @Update 注解 SQL 条件更新（未执行三态谓词 + 原因留痕 + deleted=0）
        assertThat(updateSql("casCancelBatch", String.class, String.class, String.class))
                .contains("status = 'CANCELLED'")
                .contains("cancel_reason = #{reason}")
                .contains("WHERE m04_order_no = #{m04OrderNo}")
                .contains("status IN ('CREATED', 'SIGNED', 'CHECKED')")
                .contains("deleted = 0")
                .doesNotContain("EXECUTING");
    }

    @Test
    @DisplayName("⑥ 作废撤销：与停嘱同款 CAS 面（kind=CANCELLED 仅日志语义区分，撤销谓词一致）")
    void onOrderTerminalCancelledSharesSameCasFace() {
        when(executionMapper.casCancelBatch(ORDER_NO, "开立有误", "system")).thenReturn(0);

        // 0 行=无未执行执行单/重复投递已撤销——幂等达成，不抛异常
        assertThatCode(() -> service.onOrderTerminal(ORDER_NO, "开立有误", IOrderExecutionService.TerminalKind.CANCELLED))
                .doesNotThrowAnyException();

        verify(executionMapper).casCancelBatch(ORDER_NO, "开立有误", "system");
    }

    @Test
    @DisplayName("⑦ 乱序防御：计划先于转抄到达（类型快照缺行）warn+跳过，零取号零落单")
    void onPlanGeneratedSkipsWhenSnapshotMissing() {
        when(executionMapper.selectOne(any())).thenReturn(null);

        service.onPlanGenerated(ORDER_NO, VISIT, 7L, LocalDate.of(2026, 10, 3), planNos(3), planTimes(3));

        // 乱序防御锚：无快照即无类型面，跳过等转抄事件先到（不推测类型落单）
        verify(executionMapper, never()).insertIgnorePlanConflict(any(OrderExecution.class));
        verifyNoInteractions(seqGate);
        verify(wardPatientMapper, never()).selectOne(any());
    }

    // ===================== 补充覆盖锚 =====================

    @Test
    @DisplayName("投影缺行防御：转抄时病区患者投影无在区行（ward_id 不可得）warn+跳过，零落单")
    void onOrderTransferredSkipsWhenProjectionMissing() {
        when(wardPatientMapper.selectOne(any())).thenReturn(null);

        service.onOrderTransferred(ORDER_NO, VISIT, 7L, "drug", TRANSFERRED_AT);

        // ward_id NOT NULL 无值可落：跳过而非以空值/占位病区落单（归属错误比缺单更难纠）
        verify(executionMapper, never()).insertIgnorePlanConflict(any(OrderExecution.class));
        verifyNoInteractions(seqGate);
    }

    @Test
    @DisplayName("计划批量空集：planNos 空数组零循环零写（空批合法——无计划面零负担）")
    void onPlanGeneratedWithEmptyPlansSkipsAllSql() {
        when(executionMapper.selectOne(any())).thenReturn(snapshotRow("drug"));

        service.onPlanGenerated(ORDER_NO, VISIT, 7L, LocalDate.of(2026, 10, 3), List.of(), List.of());

        verify(executionMapper, never()).insertIgnorePlanConflict(any(OrderExecution.class));
        verifyNoInteractions(seqGate);
        verify(wardPatientMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("计划落单投影缺行防御：快照在而投影缺行（转科窗口/未登记）warn+跳过，零落单")
    void onPlanGeneratedSkipsWhenProjectionMissing() {
        when(executionMapper.selectOne(any())).thenReturn(snapshotRow("drug"));
        when(wardPatientMapper.selectOne(any())).thenReturn(null);

        service.onPlanGenerated(ORDER_NO, VISIT, 7L, LocalDate.of(2026, 10, 3), planNos(2), planTimes(2));

        verify(executionMapper, never()).insertIgnorePlanConflict(any(OrderExecution.class));
        verifyNoInteractions(seqGate);
    }

    @Test
    @DisplayName("计划时点文本非法：非 HH:mm 二十四小时制形态拒收（ISE 死信留痕，禁推测落单）")
    void onPlanGeneratedRejectsMalformedPlanTimeText() {
        when(executionMapper.selectOne(any())).thenReturn(snapshotRow("drug"));
        when(wardPatientMapper.selectOne(any())).thenReturn(projection());
        when(seqGate.nextNo("EX")).thenReturn("EX2026100300001");

        assertThatThrownBy(() -> service.onPlanGenerated(
                        ORDER_NO, VISIT, 7L, LocalDate.of(2026, 10, 3), List.of("PL2026100300001"), List.of("8点")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("计划时点文本非法");
        verify(executionMapper, never()).insertIgnorePlanConflict(any(OrderExecution.class));
    }

    @Test
    @DisplayName("操作者上下文透传：登录上下文在位时审计列落当前操作者（服务面直调联动场景）")
    void onOrderTransferredStampsOperatorFromContext() {
        when(wardPatientMapper.selectOne(any())).thenReturn(projection());
        when(seqGate.nextNo("EX")).thenReturn("EX2026100200001");
        when(executionMapper.insertIgnorePlanConflict(any(OrderExecution.class)))
                .thenReturn(1);
        OperatorContextHolder.set("nurse-01");

        service.onOrderTransferred(ORDER_NO, VISIT, 7L, "drug", TRANSFERRED_AT);

        verify(executionMapper).insertIgnorePlanConflict(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getCreatedBy()).isEqualTo("nurse-01");
        assertThat(rowCaptor.getValue().getUpdatedBy()).isEqualTo("nurse-01");
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 病区患者投影行替身（生成域归属回填取数面：ward_id/bed_no）。 */
    private NursingWardPatient projection() {
        NursingWardPatient row = new NursingWardPatient();
        row.setWardId(WARD);
        row.setBedNo(BED);
        row.setVisitId(VISIT);
        row.setPatientId(7L);
        return row;
    }

    /** 转抄类型快照行替身（m04_plan_no NULL 的快照载体，execItem 承载占位与类型）。 */
    private OrderExecution snapshotRow(String transferType) {
        OrderExecution row = new OrderExecution();
        row.setM04OrderNo(ORDER_NO);
        row.setM04PlanNo(null);
        row.setExecItemCode(ORDER_NO);
        row.setExecItemName(transferType);
        return row;
    }

    /** 计划号集替身（PL+yyyyMMdd+5 位流水，与 M04 计划号形态同源）。 */
    private List<String> planNos(int count) {
        List<String> nos = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            nos.add("PL20261003" + String.format("%05d", i));
        }
        return nos;
    }

    /** 计划时点集替身（HH:mm 二十四小时制，08:00 起逐小时）。 */
    private List<String> planTimes(int count) {
        List<String> times = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            times.add(String.format("%02d:00", 8 + i));
        }
        return times;
    }

    /** 直读幂等插入方法 @Insert 注解 SQL（GC26 可执行锚：uk_execution_plan ON CONFLICT 形态）。 */
    private String insertSql() {
        Insert insert;
        try {
            insert = OrderExecutionMapper.class
                    .getMethod("insertIgnorePlanConflict", OrderExecution.class)
                    .getAnnotation(Insert.class);
        } catch (NoSuchMethodException e) {
            return fail("mapper 方法不存在：insertIgnorePlanConflict", e);
        }
        assertThat(insert)
                .as("幂等插入必须为 @Insert 注解 SQL（ON CONFLICT DO NOTHING 形态）")
                .isNotNull();
        return String.join("", insert.value());
    }

    /** 直读 mapper 方法 @Update 注解 SQL（GC26 可执行锚：条件更新必须为注解 SQL 承载）。 */
    private String updateSql(String method, Class<?>... paramTypes) {
        Update update;
        try {
            update = OrderExecutionMapper.class.getMethod(method, paramTypes).getAnnotation(Update.class);
        } catch (NoSuchMethodException e) {
            return fail("mapper 方法不存在：" + method, e);
        }
        assertThat(update).as("条件更新必须为 @Update 注解 SQL（GC26）").isNotNull();
        return String.join("", update.value());
    }
}
