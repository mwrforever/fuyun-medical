package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.PatientDisplayName;
import com.fuyun.patient.api.PatientNameQuery;
import com.fuyun.pharmacy.api.DispenseCompletedPayload;
import com.fuyun.pharmacy.api.DispenseReturnedPayload;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.cache.PharmacySeqGate;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.dto.DispensePlanGenerateRequest;
import com.fuyun.pharmacy.dto.DispenseReturnRequest;
import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.entity.DispenseItem;
import com.fuyun.pharmacy.entity.DispensePlan;
import com.fuyun.pharmacy.entity.Drug;
import com.fuyun.pharmacy.entity.DrugBatch;
import com.fuyun.pharmacy.entity.OrderMedication;
import com.fuyun.pharmacy.entity.ReviewTask;
import com.fuyun.pharmacy.entity.StockLedger;
import com.fuyun.pharmacy.internal.PharmacyDomainEvent;
import com.fuyun.pharmacy.mapper.DispenseItemMapper;
import com.fuyun.pharmacy.mapper.DispenseMapper;
import com.fuyun.pharmacy.mapper.DispensePlanMapper;
import com.fuyun.pharmacy.mapper.DrugBatchMapper;
import com.fuyun.pharmacy.mapper.DrugMapper;
import com.fuyun.pharmacy.mapper.OrderMedicationMapper;
import com.fuyun.pharmacy.mapper.ReviewTaskMapper;
import com.fuyun.pharmacy.service.IBatchSelectService;
import com.fuyun.pharmacy.vo.DispensePlanLabelVO;
import com.fuyun.pharmacy.vo.DispensePlanVO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 住院摆药计划服务单测（P2 PR-3 Task 8，Step 1–3）：计划生成（APPROVED 前置/PH-1025/长期
 * 频次分解逐次/PIVAS 判定/uk 幂等——DO NOTHING 幂等插入影响行数语义）、摆药流五步（pick
 * 预校验+排批/verify 双签+贴签/issue 调剂行四列+库存三连/deliver 状态不变时间线半步/receive
 * 事件载荷全字段断言）与 PH-1024/PH-1026 状态守卫、退药回补（部分/全部+returned 事件）与
 * 停嘱/出院两路作废。
 * MP 3.5.17 单测范式：TableInfoHelper 手工注册 + baseMapper/entityClass 反射注入；
 * Db 批量通道静态桩（DispenseThreeStepTest 同款）。
 */
@ExtendWith(MockitoExtension.class)
class DispensePlanServiceImplTest {

    /** M04 医嘱号（夹具定位键） */
    private static final String ORDER_NO = "M20261002001";

    /** I 型 14 位合法住院就诊号 */
    private static final String VISIT = "I2026100200001";

    /** 目标病区编码（请求体显式入参裁决承载） */
    private static final String WARD = "W01";

    /** 北京钟面（时区纪律专项 B 类：长期分解次日时点期望与生产同源口径） */
    private static final ZoneId BEIJING_TZ = ZoneId.of("Asia/Shanghai");

    @Mock
    private DispensePlanMapper planMapper;

    @Mock
    private OrderMedicationMapper medicationMapper;

    @Mock
    private ReviewTaskMapper reviewTaskMapper;

    @Mock
    private DispenseMapper dispenseMapper;

    @Mock
    private DispenseItemMapper dispenseItemMapper;

    @Mock
    private DrugBatchMapper drugBatchMapper;

    @Mock
    private DrugMapper drugMapper;

    @Mock
    private IBatchSelectService batchSelectService;

    @Mock
    private PharmacySeqGate seqGate;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private PatientNameQuery patientNameQuery;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体均须手工注册表信息
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DispensePlan.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Dispense.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DispenseItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StockLedger.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Drug.class);
    }

    @BeforeEach
    void loginOperator() {
        // 数字工号（picked_by/verified_by BIGINT 落值的 contextOperatorId 解析面）
        OperatorContextHolder.set("1001");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    private DispensePlanServiceImpl newService() {
        // 构造器十二参直注（objectMapper 真实例承载 items/traceCodes JSON 读写）；
        // ServiceImpl 继承字段 baseMapper/entityClass 反射注入（Global Constraints 单测范式）
        DispensePlanServiceImpl impl = new DispensePlanServiceImpl(
                planMapper,
                medicationMapper,
                reviewTaskMapper,
                dispenseMapper,
                dispenseItemMapper,
                drugBatchMapper,
                drugMapper,
                batchSelectService,
                seqGate,
                events,
                new ObjectMapper(),
                patientNameQuery);
        ReflectionTestUtils.setField(impl, "baseMapper", planMapper);
        ReflectionTestUtils.setField(impl, "entityClass", DispensePlan.class);
        return impl;
    }

    /** 医嘱快照夹具（items=口服头孢呋辛酯片单行；freqCode 可变承载长期/临时两形态） */
    private OrderMedication medication(String freqCode, String route) {
        OrderMedication medication = new OrderMedication();
        medication.setId(500L);
        medication.setM04OrderNo(ORDER_NO);
        medication.setVisitId(VISIT);
        medication.setPatientId(700101L);
        medication.setFreqCode(freqCode);
        medication.setItems("[{\"itemSeq\":1,\"itemCode\":\"D-IT-001\",\"itemName\":\"头孢呋辛酯片\",\"dosage\":\"0.5g\","
                + "\"unit\":\"g\",\"route\":\"" + route + "\",\"quantity\":\"2\",\"itemType\":\"DRUG\"}]");
        return medication;
    }

    /** 审方任务夹具（状态可变） */
    private ReviewTask task(String status) {
        ReviewTask task = new ReviewTask();
        task.setId(600L);
        task.setOrderMedicationId(500L);
        task.setStatus(status);
        return task;
    }

    /** 摆药计划夹具（id=900/DP 号；状态与类型可变） */
    private DispensePlan plan(String status, String planType) {
        DispensePlan plan = new DispensePlan();
        plan.setId(900L);
        plan.setPlanNo("DP2026100200001");
        plan.setM04OrderNo(ORDER_NO);
        plan.setVisitId(VISIT);
        plan.setPatientId(700101L);
        plan.setWardId(WARD);
        plan.setPlanType(planType);
        plan.setPlanTime(OffsetDateTime.now(BEIJING_TZ));
        plan.setStatus(status);
        plan.setLabelPrinted(false);
        plan.setPickedBy(1001L);
        plan.setVerifiedBy(1002L);
        return plan;
    }

    /** 药品字典对照夹具（itemCode→drugId=11） */
    private Drug drug() {
        Drug drug = new Drug();
        drug.setId(11L);
        drug.setItemCode("D-IT-001");
        return drug;
    }

    /** FEFO 足量批次夹具（id=55） */
    private DrugBatch batch() {
        DrugBatch batch = new DrugBatch();
        batch.setId(55L);
        batch.setBatchNo("B20260601");
        batch.setQuantity(new BigDecimal("50"));
        batch.setLockedQty(BigDecimal.ZERO);
        return batch;
    }

    /** 住院调剂行夹具（issue 后回读面：dispensePlanNo 关联+CHECKED/DELIVERED 态可变） */
    private Dispense inpatientDispense(String status) {
        Dispense dispense = new Dispense();
        dispense.setId(800L);
        dispense.setDispenseNo("D20261002000001");
        dispense.setDispenseType("INPATIENT_DOSE");
        dispense.setPrescriptionId(0L);
        dispense.setRxNo("DP2026100200001");
        dispense.setPatientId(700101L);
        dispense.setVisitId(VISIT);
        dispense.setStorehouse("OUTP_PHARM");
        dispense.setStatus(status);
        dispense.setWardId(WARD);
        dispense.setM04OrderNo(ORDER_NO);
        dispense.setDispensePlanNo("DP2026100200001");
        return dispense;
    }

    /** 住院调剂明细夹具（prescription_item_id 双语义承载医嘱明细 itemSeq=1） */
    private DispenseItem inpatientItem(BigDecimal issued, BigDecimal returned) {
        DispenseItem item = new DispenseItem();
        item.setId(1L);
        item.setDispenseId(800L);
        item.setPrescriptionItemId(1L);
        item.setDrugId(11L);
        item.setItemCode("D-IT-001");
        item.setRequestedQty(issued);
        item.setIssuedQty(issued);
        item.setReturnedQty(returned);
        item.setBatchId(55L);
        item.setBatchNo("B20260601");
        item.setTraceCodes("[]");
        item.setItemStatus("NORMAL");
        return item;
    }

    // ===================== Step 1：计划生成（TDD ①–⑤） =====================

    @Test
    @DisplayName("①③ 生成·APPROVED 前置通过：tid 长期医嘱分解次日 08:00/12:00/16:00 三计划（北京钟面）+口服判定 SINGLE_DOSE")
    void generateApprovedLongOrderDecomposesNextDayByFreq() {
        DispensePlanServiceImpl impl = newService();
        when(medicationMapper.selectOne(any())).thenReturn(medication("tid", "口服"));
        when(reviewTaskMapper.selectOne(any())).thenReturn(task("APPROVED"));
        // 首查既有=空、落库后重查=新插三行（连续 stub）
        when(planMapper.selectList(any()))
                .thenReturn(
                        List.of(),
                        List.of(
                                plan("CREATED", "SINGLE_DOSE"),
                                plan("CREATED", "SINGLE_DOSE"),
                                plan("CREATED", "SINGLE_DOSE")));
        when(seqGate.nextNo("DP")).thenReturn("DP2026100200001", "DP2026100200002", "DP2026100200003");

        List<DispensePlanVO> result = impl.generate(new DispensePlanGenerateRequest(ORDER_NO, WARD));

        // 逐时点三计划幂等落库（次日北京钟面 08:00/12:00/16:00——V904 tid 种子时点逐字同源）
        ArgumentCaptor<DispensePlan> insertCaptor = ArgumentCaptor.forClass(DispensePlan.class);
        verify(planMapper, Mockito.times(3)).insertIgnoreOrderTimeConflict(insertCaptor.capture());
        LocalDate nextDay = LocalDate.now(BEIJING_TZ).plusDays(1);
        List<OffsetDateTime> expectedTimes =
                List.of(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(16, 0)).stream()
                        .map(point -> LocalDateTime.of(nextDay, point)
                                .atZone(BEIJING_TZ)
                                .toOffsetDateTime())
                        .toList();
        assertThat(insertCaptor.getAllValues())
                .extracting(DispensePlan::getPlanTime)
                .containsExactlyElementsOf(expectedTimes);
        // 行基面：SINGLE_DOSE（口服判定）/CREATED/wardId 显式入参/计划号 DP 流水
        assertThat(insertCaptor.getAllValues()).allSatisfy(row -> {
            assertThat(row.getPlanType()).isEqualTo("SINGLE_DOSE");
            assertThat(row.getStatus()).isEqualTo("CREATED");
            assertThat(row.getWardId()).isEqualTo(WARD);
            assertThat(row.getVisitId()).isEqualTo(VISIT);
            assertThat(row.getPatientId()).isEqualTo(700101L);
            assertThat(row.getPlanNo()).startsWith("DP");
        });
        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("② 生成·未审方 PH-1025 拒绝（review_task=PENDING 零落库）")
    void generateRejectsUnapprovedOrder() {
        DispensePlanServiceImpl impl = newService();
        when(medicationMapper.selectOne(any())).thenReturn(medication("qd", "口服"));
        when(reviewTaskMapper.selectOne(any())).thenReturn(task("PENDING"));

        assertThatThrownBy(() -> impl.generate(new DispensePlanGenerateRequest(ORDER_NO, WARD)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未审方通过")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_PLAN_ORDER_INVALID);
        verify(planMapper, never()).insertIgnoreOrderTimeConflict(any(DispensePlan.class));
    }

    @Test
    @DisplayName("② 生成·医嘱快照缺失同样定性 PH-1025（医嘱非住院来源口径）")
    void generateRejectsMissingSnapshot() {
        DispensePlanServiceImpl impl = newService();
        when(medicationMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> impl.generate(new DispensePlanGenerateRequest(ORDER_NO, WARD)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("住院医嘱快照不存在")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_PLAN_ORDER_INVALID);
    }

    @Test
    @DisplayName("④ 生成·PIVAS 判定：用法含「静脉滴注」→PIVAS + qd 次日单时点计划")
    void generateJudgesPivasByIntravenousRoute() {
        DispensePlanServiceImpl impl = newService();
        when(medicationMapper.selectOne(any())).thenReturn(medication("qd", "静脉滴注"));
        when(reviewTaskMapper.selectOne(any())).thenReturn(task("APPROVED"));
        when(planMapper.selectList(any())).thenReturn(List.of(), List.of(plan("CREATED", "PIVAS")));
        when(seqGate.nextNo("DP")).thenReturn("DP2026100200001");

        impl.generate(new DispensePlanGenerateRequest(ORDER_NO, WARD));

        // 静脉族用法归 PIVAS 静配链（后续 pick 排批/verify 贴签/INPATIENT_PIVA 映射的消费锚）
        ArgumentCaptor<DispensePlan> insertCaptor = ArgumentCaptor.forClass(DispensePlan.class);
        verify(planMapper).insertIgnoreOrderTimeConflict(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getPlanType()).isEqualTo("PIVAS");
        assertThat(insertCaptor.getValue().getPlanTime())
                .isEqualTo(LocalDateTime.of(LocalDate.now(BEIJING_TZ).plusDays(1), LocalTime.of(8, 0))
                        .atZone(BEIJING_TZ)
                        .toOffsetDateTime());
    }

    @Test
    @DisplayName("⑤ 生成·uk 幂等：同医嘱同给药时点既有计划全命中→零新建零 insert，返回既有清单（稳定输出）")
    void generateIsIdempotentOnExistingOrderTimePairs() {
        DispensePlanServiceImpl impl = newService();
        when(medicationMapper.selectOne(any())).thenReturn(medication("tid", "口服"));
        when(reviewTaskMapper.selectOne(any())).thenReturn(task("APPROVED"));
        // 既有=次日三时点全量（uk_dispense_plan_order_time 全命中）
        List<DispensePlan> existing =
                List.of(planAt(LocalTime.of(8, 0)), planAt(LocalTime.of(12, 0)), planAt(LocalTime.of(16, 0)));
        when(planMapper.selectList(any())).thenReturn(existing);

        List<DispensePlanVO> result = impl.generate(new DispensePlanGenerateRequest(ORDER_NO, WARD));

        // 重复 generate 幂等：零新建（uk 锚跳过）、返回既有清单
        verify(planMapper, never()).insertIgnoreOrderTimeConflict(any(DispensePlan.class));
        assertThat(result).hasSize(3);
    }

    /** 既有计划行夹具（指定给药时点——uk 幂等用例锚） */
    private DispensePlan planAt(LocalTime point) {
        DispensePlan row = plan("CREATED", "SINGLE_DOSE");
        row.setPlanTime(LocalDateTime.of(LocalDate.now(BEIJING_TZ).plusDays(1), point)
                .atZone(BEIJING_TZ)
                .toOffsetDateTime());
        return row;
    }

    @Test
    @DisplayName("生成·临时医嘱（freqCode=null）单次即刻计划 + 同医嘱已有行不再新建（显式请求幂等锚）")
    void generateTemporaryOrderCreatesSingleImmediatePlan() {
        DispensePlanServiceImpl impl = newService();
        when(medicationMapper.selectOne(any())).thenReturn(medication(null, "口服"));
        when(reviewTaskMapper.selectOne(any())).thenReturn(task("APPROVED"));
        when(planMapper.selectList(any())).thenReturn(List.of(), List.of(plan("CREATED", "SINGLE_DOSE")));
        when(seqGate.nextNo("DP")).thenReturn("DP2026100200001");

        OffsetDateTime before = OffsetDateTime.now(BEIJING_TZ);
        impl.generate(new DispensePlanGenerateRequest(ORDER_NO, WARD));
        OffsetDateTime after = OffsetDateTime.now(BEIJING_TZ);

        // 单次即刻：planTime 落在调用窗口内（now 北京钟面）
        ArgumentCaptor<DispensePlan> insertCaptor = ArgumentCaptor.forClass(DispensePlan.class);
        verify(planMapper).insertIgnoreOrderTimeConflict(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getPlanTime()).isBetween(before, after);

        // 重复 generate（已有计划行）：临时单次不再新建
        when(planMapper.selectList(any())).thenReturn(List.of(plan("CREATED", "SINGLE_DOSE")));
        impl.generate(new DispensePlanGenerateRequest(ORDER_NO, WARD));
        verify(planMapper, Mockito.times(1)).insertIgnoreOrderTimeConflict(any(DispensePlan.class));
    }

    // ===================== Step 2：摆药流五步（TDD 五步+PH-1024） =====================

    @Test
    @DisplayName("pick：CREATED→PICKING（摆药师 1001 CAS 留痕）+ FEFO 预校验通过")
    void pickTransitionsToPickingWithStockPrecheck() {
        DispensePlanServiceImpl impl = newService();
        when(planMapper.selectOne(any())).thenReturn(plan("CREATED", "SINGLE_DOSE"));
        when(planMapper.casPick(900L, 1001L)).thenReturn(1);
        when(medicationMapper.selectOne(any())).thenReturn(medication("qd", "口服"));
        when(drugMapper.selectList(any())).thenReturn(List.of(drug()));
        when(batchSelectService.selectForDispense(11L, "OUTP_PHARM", new BigDecimal("2")))
                .thenReturn(batch());

        impl.pick("DP2026100200001");

        // 摆药师 CAS 留痕 + 预校验选批触达（只读面——真锁定归 issue）
        verify(planMapper).casPick(900L, 1001L);
        verify(batchSelectService).selectForDispense(11L, "OUTP_PHARM", new BigDecimal("2"));
        verify(drugBatchMapper, never()).lockQuantity(anyLong(), any());
    }

    @Test
    @DisplayName("pick·PIVAS 链排批：DPB 排批号回填（updateById 补丁）")
    void pickPivasAssignsBatchNumber() {
        DispensePlanServiceImpl impl = newService();
        when(planMapper.selectOne(any())).thenReturn(plan("CREATED", "PIVAS"));
        when(planMapper.casPick(900L, 1001L)).thenReturn(1);
        when(medicationMapper.selectOne(any())).thenReturn(medication("qd", "静脉滴注"));
        when(drugMapper.selectList(any())).thenReturn(List.of(drug()));
        when(batchSelectService.selectForDispense(11L, "OUTP_PHARM", new BigDecimal("2")))
                .thenReturn(batch());
        when(seqGate.nextNo("DPB")).thenReturn("DPB20261002001");

        impl.pick("DP2026100200001");

        // PIVAS 排批号=DPB+yyyyMMdd+3 位（补丁仅携 id+排批号，EX-24 同款）
        ArgumentCaptor<DispensePlan> patchCaptor = ArgumentCaptor.forClass(DispensePlan.class);
        verify(planMapper).updateById(patchCaptor.capture());
        assertThat(patchCaptor.getValue().getPivasBatchNo()).isEqualTo("DPB20261002001");
        assertThat(patchCaptor.getValue().getId()).isEqualTo(900L);
    }

    @Test
    @DisplayName("状态守卫：非 CREATED pick / 非 PICKING verify / 非 PICKED issue / 非 CHECKED deliver——一律 PH-1024")
    void stateGuardsRejectWrongStatesWithPh1024() {
        DispensePlanServiceImpl impl = newService();
        // pick：CAS 0 行（状态违例/并发被抢）
        when(planMapper.selectOne(any())).thenReturn(plan("DELIVERED", "SINGLE_DOSE"));
        assertThatThrownBy(() -> impl.pick("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED);

        // verify：非 PICKING（换核对人 1002 避开同人双签先行守卫；casVerify 未桩 0 行=状态违例）
        OperatorContextHolder.set("1002");
        assertThatThrownBy(() -> impl.verify("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED);
        OperatorContextHolder.set("1001");

        // issue：非 PICKED
        assertThatThrownBy(() -> impl.issue("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态不允许出库")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED);

        // deliver：非 CHECKED
        assertThatThrownBy(() -> impl.deliver("DP2026100200001", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态不允许配送交接")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("verify：PICKING→PICKED 双签（1002≠摆药师 1001）+ PIVAS 链 label_printed=true（贴签核对）")
    void verifyTransitionsWithDualSignAndPivasLabel() {
        DispensePlanServiceImpl impl = newService();
        when(planMapper.selectOne(any())).thenReturn(plan("PICKING", "PIVAS"));
        when(planMapper.casVerify(900L, 1002L, true)).thenReturn(1);
        OperatorContextHolder.set("1002");

        impl.verify("DP2026100200001");

        // 核对第二签留痕 + PIVAS 贴签标记随 CAS 单语句置位
        verify(planMapper).casVerify(900L, 1002L, true);
    }

    @Test
    @DisplayName("verify·同人双签拒：核对人=摆药师 1001 → PH-1011（分权硬守卫）")
    void verifyRejectsDualSignConflict() {
        DispensePlanServiceImpl impl = newService();
        when(planMapper.selectOne(any())).thenReturn(plan("PICKING", "SINGLE_DOSE"));

        assertThatThrownBy(() -> impl.verify("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不得同一人双签")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DUAL_SIGN_CONFLICT);
        verify(planMapper, never()).casVerify(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("issue：PICKED→CHECKED + 落调剂行（住院四列/type 映射/rx_no=计划号/prescription_id=0 占位）+ 锁定扣减流水三连断言")
    void issueCreatesInpatientDispenseRowWithStockDeduction() {
        DispensePlanServiceImpl impl = newService();
        DispensePlan picked = plan("PICKED", "SINGLE_DOSE");
        when(planMapper.selectOne(any())).thenReturn(picked);
        when(planMapper.casStatus(900L, "PICKED", "CHECKED")).thenReturn(1);
        when(medicationMapper.selectOne(any())).thenReturn(medication("qd", "口服"));
        when(drugMapper.selectList(any())).thenReturn(List.of(drug()));
        when(batchSelectService.selectForDispense(11L, "OUTP_PHARM", new BigDecimal("2")))
                .thenReturn(batch());
        when(drugBatchMapper.lockQuantity(55L, new BigDecimal("2"))).thenReturn(1);
        when(drugBatchMapper.deductLocked(55L, new BigDecimal("2"))).thenReturn(1);

        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.issue("DP2026100200001");

            // 调剂行住院四列全量断言：type 按 plan_type 映射/ward_id/m04_order_no/dispense_plan_no；
            // 诞生即 CHECKED（前三态由计划行承载）；rx_no NOT NULL 列承载计划号、prescription_id=0 占位
            ArgumentCaptor<Dispense> dispenseCaptor = ArgumentCaptor.forClass(Dispense.class);
            verify(dispenseMapper).insert(dispenseCaptor.capture());
            Dispense inserted = dispenseCaptor.getValue();
            assertThat(inserted.getDispenseType()).isEqualTo("INPATIENT_DOSE");
            assertThat(inserted.getWardId()).isEqualTo(WARD);
            assertThat(inserted.getM04OrderNo()).isEqualTo(ORDER_NO);
            assertThat(inserted.getDispensePlanNo()).isEqualTo("DP2026100200001");
            assertThat(inserted.getStatus()).isEqualTo("CHECKED");
            assertThat(inserted.getPrescriptionId()).isEqualTo(0L);
            assertThat(inserted.getRxNo()).isEqualTo("DP2026100200001");
            assertThat(inserted.getVisitId()).isEqualTo(VISIT);

            // 选批-锁定-扣减三连（门诊同款条件更新通道复用断言）
            verify(drugBatchMapper).lockQuantity(55L, new BigDecimal("2"));
            verify(drugBatchMapper).deductLocked(55L, new BigDecimal("2"));

            // 出库流水（负数量 ISSUE 行、refDoc=计划号）与明细行（批次回填+itemSeq 双语义锚）各一次批插
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Object>> batchCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.saveBatch(batchCaptor.capture()), Mockito.times(2));
            List<Object> allRows =
                    batchCaptor.getAllValues().stream().flatMap(List::stream).collect(Collectors.toList());
            List<StockLedger> ledgers = allRows.stream()
                    .filter(StockLedger.class::isInstance)
                    .map(StockLedger.class::cast)
                    .collect(Collectors.toList());
            assertThat(ledgers).hasSize(1);
            assertThat(ledgers.get(0).getQuantity()).isEqualTo(new BigDecimal("-2"));
            assertThat(ledgers.get(0).getRefDoc()).isEqualTo("DP2026100200001");
            List<DispenseItem> items = allRows.stream()
                    .filter(DispenseItem.class::isInstance)
                    .map(DispenseItem.class::cast)
                    .collect(Collectors.toList());
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getBatchId()).isEqualTo(55L);
            assertThat(items.get(0).getBatchNo()).isEqualTo("B20260601");
            assertThat(items.get(0).getPrescriptionItemId()).isEqualTo(1L);
            assertThat(items.get(0).getIssuedQty()).isEqualTo(new BigDecimal("2"));
        }
    }

    @Test
    @DisplayName("issue·库存不足拒：选批 null → PH-1010 整事务回滚（零批插零流水）")
    void issueRejectsWhenStockInsufficient() {
        DispensePlanServiceImpl impl = newService();
        when(planMapper.selectOne(any())).thenReturn(plan("PICKED", "SINGLE_DOSE"));
        when(planMapper.casStatus(900L, "PICKED", "CHECKED")).thenReturn(1);
        when(medicationMapper.selectOne(any())).thenReturn(medication("qd", "口服"));
        when(drugMapper.selectList(any())).thenReturn(List.of(drug()));
        when(batchSelectService.selectForDispense(11L, "OUTP_PHARM", new BigDecimal("2")))
                .thenReturn(null);

        assertThatThrownBy(() -> impl.issue("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.STOCK_INSUFFICIENT);
        verify(dispenseMapper, never()).insert(any(Dispense.class));
    }

    @Test
    @DisplayName("deliver：CHECKED 态内 issued_at 时间线半步——状态不迁移（钉死仍 CHECKED）+ PH-1026 未配送不可签收")
    void deliverMarksTimelineWithoutStateMigrationAndReceiveRequiresHandover() {
        DispensePlanServiceImpl impl = newService();
        // deliver：markDeliverHandover 置 issued_at（半步）——本测试钉死「不迁移状态」的语句形态：
        // mapper 语句仅 SET issued_at 且谓词限 CHECKED（见 DispensePlanMapper.markDeliverHandover）
        when(planMapper.selectOne(any())).thenReturn(plan("CHECKED", "SINGLE_DOSE"));
        when(planMapper.markDeliverHandover(eq(900L), any(OffsetDateTime.class)))
                .thenReturn(1);

        impl.deliver("DP2026100200001", "工勤-张三");

        // 时间线半步仅置 issued_at——计划状态守卫语句不携带状态迁移（钉死锚）
        verify(planMapper).markDeliverHandover(eq(900L), any(OffsetDateTime.class));
        verify(planMapper, never()).casStatus(anyLong(), anyString(), anyString());

        // 未配送（issued_at 缺位）直接签收 → PH-1026
        DispensePlan notDelivered = plan("CHECKED", "SINGLE_DOSE");
        notDelivered.setIssuedAt(null);
        when(planMapper.selectOne(any())).thenReturn(notDelivered);
        assertThatThrownBy(() -> impl.receive("DP2026100200001", 2001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未配送不可签收")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.WARD_RECEIVE_INVALID);
    }

    @Test
    @DisplayName("receive：CHECKED→DELIVERED CAS + 调剂行同步 + completed 事件载荷全字段断言（住院四字段全量）")
    void receivePublishesCompletedEventWithFullInpatientPayload() {
        DispensePlanServiceImpl impl = newService();
        DispensePlan checked = plan("CHECKED", "PIVAS");
        checked.setIssuedAt(OffsetDateTime.now(BEIJING_TZ));
        when(planMapper.selectOne(any())).thenReturn(checked);
        when(planMapper.markDelivered(eq(900L), eq(2001L), any(OffsetDateTime.class)))
                .thenReturn(1);
        when(dispenseMapper.selectOne(any())).thenReturn(inpatientDispense("CHECKED"));
        when(dispenseMapper.casStatus(800L, "CHECKED", "DELIVERED")).thenReturn(1);
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(inpatientItem(new BigDecimal("2"), BigDecimal.ZERO)));

        impl.receive("DP2026100200001", 2001L);

        // 签收 CAS + 调剂行同步迁移
        verify(planMapper).markDelivered(eq(900L), eq(2001L), any(OffsetDateTime.class));
        verify(dispenseMapper).casStatus(800L, "CHECKED", "DELIVERED");
        // 事务内发布 completed：id 28 + V1111 住院四字段全量载荷逐字段断言（M05 签收衔接读面）
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        PharmacyDomainEvent event = (PharmacyDomainEvent) eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED);
        DispenseCompletedPayload payload = (DispenseCompletedPayload) event.payload();
        assertThat(payload.dispenseNo()).isEqualTo("D20261002000001");
        assertThat(payload.prescriptionId()).isNull();
        assertThat(payload.rxNo()).isNull();
        assertThat(payload.patientId()).isEqualTo(700101L);
        assertThat(payload.visitId()).isEqualTo(VISIT);
        assertThat(payload.dispenseType()).isEqualTo("INPATIENT_DOSE");
        assertThat(payload.m04OrderNo()).isEqualTo(ORDER_NO);
        assertThat(payload.wardId()).isEqualTo(WARD);
        assertThat(payload.dispensePlanNo()).isEqualTo("DP2026100200001");
        assertThat(payload.lines()).hasSize(1);
        assertThat(payload.lines().get(0).itemCode()).isEqualTo("D-IT-001");
        assertThat(payload.lines().get(0).batchNo()).isEqualTo("B20260601");
        assertThat(payload.lines().get(0).quantity()).isEqualTo("2");
        assertThat(payload.lines().get(0).traceCodes()).isEmpty();
    }

    // ===================== Step 3：退药回补与作废（TDD 三组） =====================

    @Test
    @DisplayName("退药·部分回补：DELIVERED 行部分退（restock+流水+PART_RETURNED+returned 事件 fullReturn=false）")
    void inpatientReturnPartialRestocksAndPublishesReturned() {
        DispensePlanServiceImpl impl = newService();
        when(planMapper.selectOne(any())).thenReturn(plan("DELIVERED", "SINGLE_DOSE"));
        when(dispenseMapper.selectOne(any())).thenReturn(inpatientDispense("DELIVERED"));
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(inpatientItem(new BigDecimal("2"), BigDecimal.ZERO)));
        when(drugBatchMapper.restock(55L, new BigDecimal("1"))).thenReturn(1);
        when(dispenseMapper.casStatus(800L, "DELIVERED", "PART_RETURNED")).thenReturn(1);

        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.acceptInpatientReturn(new DispenseReturnRequest(
                    null,
                    null,
                    null,
                    "DP2026100200001",
                    List.of(new DispenseReturnRequest.InpatientReturnLine("1", "1", null))));

            // 批次回补（门诊同款条件更新通道）+ 退药数回写批更
            verify(drugBatchMapper).restock(55L, new BigDecimal("1"));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> patchCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.updateBatchById(patchCaptor.capture()));
            assertThat(patchCaptor.getValue().get(0).getReturnedQty()).isEqualTo(new BigDecimal("1"));
        }
        // 调剂行 DELIVERED→PART_RETURNED（部分退终态；计划行不迁——V1110 词表无退药态）
        verify(dispenseMapper).casStatus(800L, "DELIVERED", "PART_RETURNED");
        // returned 事件（id 29 冻结载荷；住院行 prescriptionId/rxNo=null）
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        DispenseReturnedPayload payload =
                (DispenseReturnedPayload) ((PharmacyDomainEvent) eventCaptor.getValue()).payload();
        assertThat(payload.fullReturn()).isFalse();
        assertThat(payload.prescriptionId()).isNull();
        assertThat(payload.rxNo()).isNull();
        assertThat(payload.lines()).hasSize(1);
        assertThat(payload.lines().get(0).quantity()).isEqualTo("1");
    }

    @Test
    @DisplayName("退药·全部回补：全量退 → FULL_RETURNED + fullReturn=true")
    void inpatientReturnFullRestocksToFullReturned() {
        DispensePlanServiceImpl impl = newService();
        when(planMapper.selectOne(any())).thenReturn(plan("DELIVERED", "SINGLE_DOSE"));
        when(dispenseMapper.selectOne(any())).thenReturn(inpatientDispense("DELIVERED"));
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(inpatientItem(new BigDecimal("2"), BigDecimal.ZERO)));
        when(drugBatchMapper.restock(55L, new BigDecimal("2"))).thenReturn(1);
        when(dispenseMapper.casStatus(800L, "DELIVERED", "FULL_RETURNED")).thenReturn(1);

        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.acceptInpatientReturn(new DispenseReturnRequest(
                    null,
                    null,
                    null,
                    "DP2026100200001",
                    List.of(new DispenseReturnRequest.InpatientReturnLine("1", "2", null))));

            verify(dispenseMapper).casStatus(800L, "DELIVERED", "FULL_RETURNED");
        }
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(((DispenseReturnedPayload) ((PharmacyDomainEvent) eventCaptor.getValue()).payload()).fullReturn())
                .isTrue();
    }

    @Test
    @DisplayName("退药·守卫：非 DELIVERED 拒（PH-1013）/ 超可退余额拒 / 缺行拒")
    void inpatientReturnGuards() {
        DispensePlanServiceImpl impl = newService();
        // 非 DELIVERED（未签收不可退）
        when(planMapper.selectOne(any())).thenReturn(plan("CHECKED", "SINGLE_DOSE"));
        when(dispenseMapper.selectOne(any())).thenReturn(inpatientDispense("CHECKED"));
        assertThatThrownBy(() -> impl.acceptInpatientReturn(
                        new DispenseReturnRequest(null, null, null, "DP2026100200001", List.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅病区签收后可退")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED);

        // 超可退余额（issued=2 退 3 拒）
        when(dispenseMapper.selectOne(any())).thenReturn(inpatientDispense("DELIVERED"));
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(inpatientItem(new BigDecimal("2"), BigDecimal.ZERO)));
        assertThatThrownBy(() -> impl.acceptInpatientReturn(new DispenseReturnRequest(
                        null,
                        null,
                        null,
                        "DP2026100200001",
                        List.of(new DispenseReturnRequest.InpatientReturnLine("1", "3", null)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超可退余额")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED);

        // 缺行（returnLines 不含明细 itemSeq）
        assertThatThrownBy(() -> impl.acceptInpatientReturn(new DispenseReturnRequest(
                        null,
                        null,
                        null,
                        "DP2026100200001",
                        List.of(new DispenseReturnRequest.InpatientReturnLine("9", "1", null)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺行")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED);
        verify(drugBatchMapper, never()).restock(anyLong(), any());
    }

    @Test
    @DisplayName("作废·停嘱：未摆药计划（CREATED/PICKING）逐行 CANCELLED+原因留痕；CAS 0 行幂等跳过")
    void cancelByOrderTerminalVoidsPendingPlans() {
        DispensePlanServiceImpl impl = newService();
        DispensePlan picking = plan("PICKING", "SINGLE_DOSE");
        when(planMapper.selectList(any())).thenReturn(List.of(plan("CREATED", "SINGLE_DOSE"), picking));
        when(planMapper.casCancel(900L, "医嘱停止")).thenReturn(1, 0);

        impl.cancelByOrderTerminal(ORDER_NO, "医嘱停止");

        // 未摆药两态均作废（cancel_reason 留痕）；CAS 0 行=幂等跳过不上抛（消费位纪律）
        verify(planMapper, Mockito.times(2)).casCancel(900L, "医嘱停止");
    }

    @Test
    @DisplayName("作废·出院：未摆药作废 + 已摆未用（PICKED/CHECKED/DELIVERED）提示不动作（退药人工发起）")
    void cancelByVisitDischargeVoidsPendingAndWarnsUsed() {
        DispensePlanServiceImpl impl = newService();
        DispensePlan pending = plan("CREATED", "SINGLE_DOSE");
        DispensePlan used = plan("DELIVERED", "SINGLE_DOSE");
        // 首查未摆药（CREATED/PICKING）、次查已摆未用（PICKED/CHECKED/DELIVERED）
        when(planMapper.selectList(any())).thenReturn(List.of(pending), List.of(used));
        when(planMapper.casCancel(900L, "出院终清作废")).thenReturn(1);

        impl.cancelByVisitDischarge(VISIT);

        // 未摆药作废留痕；已摆未用零写面（不作废不回补——人工退药承接）
        verify(planMapper).casCancel(900L, "出院终清作废");
        verify(planMapper, Mockito.times(1)).casCancel(anyLong(), anyString());
    }

    // ===================== 查询面 =====================

    @Test
    @DisplayName("label·PIVAS 贴签数据面：脱敏患者名+排批号+双人留痕+药品明细投影；非 PIVAS 拒 PH-1024")
    void labelProjectsPivasDataPlane() {
        DispensePlanServiceImpl impl = newService();
        DispensePlan pivas = plan("PICKED", "PIVAS");
        pivas.setPivasBatchNo("DPB20261002001");
        when(planMapper.selectOne(any())).thenReturn(pivas);
        when(medicationMapper.selectOne(any())).thenReturn(medication("qd", "静脉滴注"));
        when(patientNameQuery.displayNamesOf(List.of(700101L)))
                .thenReturn(List.of(new PatientDisplayName(700101L, "张*")));

        DispensePlanLabelVO vo = impl.label("DP2026100200001");

        // 贴签数据面：脱敏名（M02 红线）/排批/双人/明细行；bedNo 恒 null（床位无 pharmacy 数据源注记）
        assertThat(vo.patientName()).isEqualTo("张*");
        assertThat(vo.pivasBatchNo()).isEqualTo("DPB20261002001");
        assertThat(vo.pickedBy()).isEqualTo(1001L);
        assertThat(vo.verifiedBy()).isEqualTo(1002L);
        assertThat(vo.bedNo()).isNull();
        assertThat(vo.items()).hasSize(1);
        assertThat(vo.items().get(0).itemCode()).isEqualTo("D-IT-001");
        assertThat(vo.items().get(0).quantity()).isEqualTo("2");

        // 非 PIVAS 链无贴签面
        when(planMapper.selectOne(any())).thenReturn(plan("PICKED", "SINGLE_DOSE"));
        assertThatThrownBy(() -> impl.label("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅 PIVAS 链")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED);
    }

    // ===================== 覆盖收口：查询/并发窗口/脏数据守卫分支（LINE=1.00 名单义务） =====================

    @Test
    @DisplayName("分页查询：三过滤组合透传+记录映射（PageResult 出参）")
    void pageFiltersAndMapsRecords() {
        DispensePlanServiceImpl impl = newService();
        when(planMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<DispensePlan> p = invocation.getArgument(0);
            p.setRecords(List.of(plan("CREATED", "SINGLE_DOSE")));
            p.setTotal(1);
            return p;
        });

        PageResult<DispensePlanVO> result = impl.page(ORDER_NO, WARD, "CREATED", 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).planNo()).isEqualTo("DP2026100200001");
        assertThat(result.content().get(0).status()).isEqualTo("CREATED");
        assertThat(result.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("生成·uk 并发窗口兜底：幂等插入 0 行（对端已落同键行）不抛不毒化——末尾重查照常执行返回既有清单")
    void generateConcurrentConflictSkipsRowWithoutPoisoningTransaction() {
        DispensePlanServiceImpl impl = newService();
        when(medicationMapper.selectOne(any())).thenReturn(medication("tid", "口服"));
        when(reviewTaskMapper.selectOne(any())).thenReturn(task("APPROVED"));
        when(planMapper.selectList(any())).thenReturn(List.of(), List.of(plan("CREATED", "SINGLE_DOSE")));
        when(seqGate.nextNo("DP")).thenReturn("DP2026100200001", "DP2026100200002", "DP2026100200003");
        // 并发对端已插入同医嘱同时点计划：ON CONFLICT DO NOTHING 整行放弃（0 行不抛——
        // 物理事务不中止，同批后续插入与末尾 selectList 均正常执行，catch 毒化形态已移除）
        when(planMapper.insertIgnoreOrderTimeConflict(any(DispensePlan.class))).thenReturn(0, 0, 0);

        List<DispensePlanVO> result = impl.generate(new DispensePlanGenerateRequest(ORDER_NO, WARD));

        // 三时点全部 0 行跳过零异常；末尾幂等重查照常触达（事务未被毒化的行为锚），
        // 输出对端已落清单（幂等收敛）
        verify(planMapper, Mockito.times(3)).insertIgnoreOrderTimeConflict(any(DispensePlan.class));
        verify(planMapper, Mockito.times(2)).selectList(any());
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("pick·预校验缺量拒：FEFO 选批 null → PH-1010 整事务回滚（CAS 一并回退）")
    void pickRejectsPrecheckStockShortage() {
        DispensePlanServiceImpl impl = newService();
        when(planMapper.selectOne(any())).thenReturn(plan("CREATED", "SINGLE_DOSE"));
        when(planMapper.casPick(900L, 1001L)).thenReturn(1);
        when(medicationMapper.selectOne(any())).thenReturn(medication("qd", "口服"));
        when(drugMapper.selectList(any())).thenReturn(List.of(drug()));
        when(batchSelectService.selectForDispense(11L, "OUTP_PHARM", new BigDecimal("2")))
                .thenReturn(null);

        assertThatThrownBy(() -> impl.pick("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("摆药预校验被拒")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.STOCK_INSUFFICIENT);
    }

    @Test
    @DisplayName("issue·三守卫：双签重申缺失拒 / 出库 CAS 并发被抢 / 锁定 0 行拒（PH-1024·PH-1010）")
    void issueGuardFailures() {
        // 守卫一：核对留痕缺失（verifiedBy=null）——PH-1024，CAS 零触达
        DispensePlanServiceImpl impl = newService();
        DispensePlan unverified = plan("PICKED", "SINGLE_DOSE");
        unverified.setVerifiedBy(null);
        when(planMapper.selectOne(any())).thenReturn(unverified);
        assertThatThrownBy(() -> impl.issue("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("出库前置守卫失败")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED);
        verify(planMapper, never()).casStatus(anyLong(), anyString(), anyString());

        // 守卫二：出库 CAS 0 行（并发被抢）——PH-1024
        when(planMapper.selectOne(any())).thenReturn(plan("PICKED", "SINGLE_DOSE"));
        when(planMapper.casStatus(900L, "PICKED", "CHECKED")).thenReturn(0);
        assertThatThrownBy(() -> impl.issue("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("出库交接并发被抢")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED);

        // 守卫三：批次锁定 0 行（并发超发）——PH-1010 整事务回滚
        when(planMapper.casStatus(900L, "PICKED", "CHECKED")).thenReturn(1);
        when(medicationMapper.selectOne(any())).thenReturn(medication("qd", "口服"));
        when(drugMapper.selectList(any())).thenReturn(List.of(drug()));
        when(batchSelectService.selectForDispense(11L, "OUTP_PHARM", new BigDecimal("2")))
                .thenReturn(batch());
        when(drugBatchMapper.lockQuantity(55L, new BigDecimal("2"))).thenReturn(0);
        assertThatThrownBy(() -> impl.issue("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("批次锁定扣减失败")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.STOCK_INSUFFICIENT);
        verify(dispenseMapper, never()).insert(any(Dispense.class));
    }

    @Test
    @DisplayName("deliver/receive·并发窗口守卫：交接 0 行 / 签收 CAS 0 行 / 调剂行缺行 / 调剂行同步被抢")
    void deliverAndReceiveLostRaceGuards() {
        DispensePlanServiceImpl impl = newService();
        // deliver：markDeliverHandover 0 行（状态漂移被抢）
        when(planMapper.selectOne(any())).thenReturn(plan("CHECKED", "SINGLE_DOSE"));
        when(planMapper.markDeliverHandover(eq(900L), any(OffsetDateTime.class)))
                .thenReturn(0);
        assertThatThrownBy(() -> impl.deliver("DP2026100200001", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("配送交接并发被抢")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED);

        // receive：markDelivered 0 行（签收 CAS 被抢）
        DispensePlan handedOver = plan("CHECKED", "SINGLE_DOSE");
        handedOver.setIssuedAt(OffsetDateTime.now(BEIJING_TZ));
        when(planMapper.selectOne(any())).thenReturn(handedOver);
        when(planMapper.markDelivered(eq(900L), eq(2001L), any(OffsetDateTime.class)))
                .thenReturn(0);
        assertThatThrownBy(() -> impl.receive("DP2026100200001", 2001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态不允许签收")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_PLAN_STATE_NOT_ALLOWED);

        // receive：计划已出库但调剂行缺行（数据不一致面显式暴露——PH-1008）
        when(planMapper.markDelivered(eq(900L), eq(2001L), any(OffsetDateTime.class)))
                .thenReturn(1);
        when(dispenseMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> impl.receive("DP2026100200001", 2001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("调剂行缺行")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_NOT_FOUND);

        // receive：调剂行状态同步 CAS 0 行（并发被抢）——PH-1009
        when(dispenseMapper.selectOne(any())).thenReturn(inpatientDispense("CHECKED"));
        when(dispenseMapper.casStatus(800L, "CHECKED", "DELIVERED")).thenReturn(0);
        assertThatThrownBy(() -> impl.receive("DP2026100200001", 2001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("签收同步并发被抢")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("receive·追溯码脏数据守卫：明细 traceCodes 非 JSON → ISE 显式暴露（禁静默吞）")
    void receiveSurfacesCorruptedTraceCodesJson() {
        DispensePlanServiceImpl impl = newService();
        DispensePlan checked = plan("CHECKED", "PIVAS");
        checked.setIssuedAt(OffsetDateTime.now(BEIJING_TZ));
        when(planMapper.selectOne(any())).thenReturn(checked);
        when(planMapper.markDelivered(eq(900L), eq(2001L), any(OffsetDateTime.class)))
                .thenReturn(1);
        when(dispenseMapper.selectOne(any())).thenReturn(inpatientDispense("CHECKED"));
        when(dispenseMapper.casStatus(800L, "CHECKED", "DELIVERED")).thenReturn(1);
        DispenseItem corrupted = inpatientItem(new BigDecimal("2"), BigDecimal.ZERO);
        corrupted.setTraceCodes("not-json");
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(corrupted));

        assertThatThrownBy(() -> impl.receive("DP2026100200001", 2001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("追溯码数据损坏");
    }

    @Test
    @DisplayName("退药·守卫收口：调剂行缺单拒 / 追溯码不一致拒 / 回补 0 行拒 / 终态 CAS 被抢 / 数量非数字拒")
    void inpatientReturnFailureGuards() {
        DispensePlanServiceImpl impl = newService();
        when(planMapper.selectOne(any())).thenReturn(plan("DELIVERED", "SINGLE_DOSE"));

        // 调剂行缺单（未出库不可退）——PH-1008
        when(dispenseMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> impl.acceptInpatientReturn(
                        new DispenseReturnRequest(null, null, null, "DP2026100200001", List.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未出库不可退药")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_NOT_FOUND);

        // 追溯码与摆药记录不一致（防回流核验拒——住院采集为空集，非空退药码即拒）——PH-1012
        when(dispenseMapper.selectOne(any())).thenReturn(inpatientDispense("DELIVERED"));
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(inpatientItem(new BigDecimal("2"), BigDecimal.ZERO)));
        assertThatThrownBy(() -> impl.acceptInpatientReturn(new DispenseReturnRequest(
                        null,
                        null,
                        null,
                        "DP2026100200001",
                        List.of(new DispenseReturnRequest.InpatientReturnLine("1", "1", List.of("T001"))))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("防回流药核验拒")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.TRACE_CODE_MISMATCH);

        // 退药行清单缺位（returnLines=null）——逐明细锚定缺行拒
        assertThatThrownBy(() -> impl.acceptInpatientReturn(
                        new DispenseReturnRequest(null, null, null, "DP2026100200001", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("住院退药缺行")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED);

        // 回补条件更新 0 行（批次状态漂移）——PH-1013
        when(drugBatchMapper.restock(55L, new BigDecimal("1"))).thenReturn(0);
        assertThatThrownBy(() -> impl.acceptInpatientReturn(new DispenseReturnRequest(
                        null,
                        null,
                        null,
                        "DP2026100200001",
                        List.of(new DispenseReturnRequest.InpatientReturnLine("1", "1", null)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("批次回补失败")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED);

        // 终态迁移 CAS 0 行（并发被抢）——PH-1009
        when(drugBatchMapper.restock(55L, new BigDecimal("1"))).thenReturn(1);
        when(dispenseMapper.casStatus(800L, "DELIVERED", "PART_RETURNED")).thenReturn(0);
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            assertThatThrownBy(() -> impl.acceptInpatientReturn(new DispenseReturnRequest(
                            null,
                            null,
                            null,
                            "DP2026100200001",
                            List.of(new DispenseReturnRequest.InpatientReturnLine("1", "1", null)))))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("终态迁移并发被抢")
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED);
            // CAS 失败后事件零发布（生产语义回批写由事务回滚承载，单测锚定发布面不触达）
            verify(events, never()).publishEvent(any());
        }

        // 退药数量非数字串——PH-1016
        assertThatThrownBy(() -> impl.acceptInpatientReturn(new DispenseReturnRequest(
                        null,
                        null,
                        null,
                        "DP2026100200001",
                        List.of(new DispenseReturnRequest.InpatientReturnLine("1", "abc", null)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("退药数量须为数字串")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.NUMERIC_FIELD_MALFORMED);
    }

    @Test
    @DisplayName("定位/解析守卫收口：计划缺单 PH-1023 / 医嘱快照缺行 / items 脏 JSON / 药品无对照 / 数量非数字")
    void lookupAndParsingFailureGuards() {
        DispensePlanServiceImpl impl = newService();

        // 计划缺单——PH-1023
        when(planMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> impl.pick("DPX404"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("摆药计划不存在")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DISPENSE_PLAN_NOT_FOUND);

        // 计划在但医嘱快照缺行（数据不一致面——计划行存在快照被删）
        when(planMapper.selectOne(any())).thenReturn(plan("CREATED", "SINGLE_DOSE"));
        when(planMapper.casPick(900L, 1001L)).thenReturn(1);
        when(medicationMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> impl.pick("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("医嘱快照缺行")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.MEDICATION_ORDER_NOT_FOUND);

        // items 快照脏 JSON——ISE 显式暴露（EX-19 C 类，禁静默吞）
        OrderMedication corrupted = medication("qd", "口服");
        corrupted.setItems("not-json");
        when(medicationMapper.selectOne(any())).thenReturn(corrupted);
        assertThatThrownBy(() -> impl.pick("DP2026100200001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("医嘱项明细快照数据损坏");

        // 药品字典无对照（收费项目未对照药品）——PH-1001
        when(medicationMapper.selectOne(any())).thenReturn(medication("qd", "口服"));
        when(drugMapper.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> impl.pick("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("药品字典无对照")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.DRUG_NOT_FOUND);

        // 摆药数量非数字串——PH-1016
        when(drugMapper.selectList(any())).thenReturn(List.of(drug()));
        OrderMedication malformedQty = medication("qd", "口服");
        malformedQty.setItems("[{\"itemSeq\":1,\"itemCode\":\"D-IT-001\",\"itemName\":\"头孢呋辛酯片\",\"dosage\":\"0.5g\","
                + "\"unit\":\"g\",\"route\":\"口服\",\"quantity\":\"abc\",\"itemType\":\"DRUG\"}]");
        when(medicationMapper.selectOne(any())).thenReturn(malformedQty);
        assertThatThrownBy(() -> impl.pick("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("摆药数量须为数字串")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.NUMERIC_FIELD_MALFORMED);
    }

    @Test
    @DisplayName("生成·plan_type 兜底：非静脉非口服用法（雾化）→ WHOLE 整包")
    void generateJudgesWholeForOtherRoutes() {
        DispensePlanServiceImpl impl = newService();
        when(medicationMapper.selectOne(any())).thenReturn(medication("qd", "雾化吸入"));
        when(reviewTaskMapper.selectOne(any())).thenReturn(task("APPROVED"));
        when(planMapper.selectList(any())).thenReturn(List.of(), List.of(plan("CREATED", "WHOLE")));
        when(seqGate.nextNo("DP")).thenReturn("DP2026100200001");

        impl.generate(new DispensePlanGenerateRequest(ORDER_NO, WARD));

        ArgumentCaptor<DispensePlan> insertCaptor = ArgumentCaptor.forClass(DispensePlan.class);
        verify(planMapper).insertIgnoreOrderTimeConflict(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getPlanType()).isEqualTo("WHOLE");
    }

    @Test
    @DisplayName("issue·空明细医嘱：零行库存零流水，调剂行照落（纯嘱托类 drug 行合法空面）")
    void issueHandlesEmptyItemLines() {
        DispensePlanServiceImpl impl = newService();
        when(planMapper.selectOne(any())).thenReturn(plan("PICKED", "SINGLE_DOSE"));
        when(planMapper.casStatus(900L, "PICKED", "CHECKED")).thenReturn(1);
        OrderMedication emptyItems = medication("qd", "口服");
        emptyItems.setItems("[]");
        when(medicationMapper.selectOne(any())).thenReturn(emptyItems);

        impl.issue("DP2026100200001");

        // 零行选批/零流水批插；调剂行仍落（流程完整性由计划行承载）
        verify(batchSelectService, never()).selectForDispense(anyLong(), anyString(), any());
        verify(dispenseMapper).insert(any(Dispense.class));
    }

    @Test
    @DisplayName("操作者守卫：登录上下文非数字标识 → PH-1016 拒（禁 NumberFormatException 直穿 500）")
    void operatorContextGuardRejectsNonNumericOperator() {
        DispensePlanServiceImpl impl = newService();
        when(planMapper.selectOne(any())).thenReturn(plan("CREATED", "SINGLE_DOSE"));
        OperatorContextHolder.set("pharmacist-zhang");

        assertThatThrownBy(() -> impl.pick("DP2026100200001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("操作者标识缺失或非数字")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PharmacyErrorCode.NUMERIC_FIELD_MALFORMED);
        OperatorContextHolder.set("1001");
    }

    @Test
    @DisplayName("药品对照重复行合并：字典同 itemCode 多行 → toMap 取首不抛重复键（数据治理兜底面）")
    void pickToleratesDuplicateDrugDictionaryRows() {
        DispensePlanServiceImpl impl = newService();
        Drug second = drug();
        second.setId(12L);
        when(planMapper.selectOne(any())).thenReturn(plan("CREATED", "SINGLE_DOSE"));
        when(planMapper.casPick(900L, 1001L)).thenReturn(1);
        when(medicationMapper.selectOne(any())).thenReturn(medication("qd", "口服"));
        // 字典重复行（同 itemCode 不同 id）——合并函数取首，预校验以首行 drugId 选批
        when(drugMapper.selectList(any())).thenReturn(List.of(drug(), second));
        when(batchSelectService.selectForDispense(11L, "OUTP_PHARM", new BigDecimal("2")))
                .thenReturn(batch());

        impl.pick("DP2026100200001");

        // 重复行不阻断摆药：取首行（drugId=11）完成预校验
        verify(batchSelectService).selectForDispense(11L, "OUTP_PHARM", new BigDecimal("2"));
    }
}
