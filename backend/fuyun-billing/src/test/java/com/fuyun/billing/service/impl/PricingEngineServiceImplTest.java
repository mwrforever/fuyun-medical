package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.api.FeeCreatedPayload;
import com.fuyun.billing.dto.FeeGenerateCommand;
import com.fuyun.billing.dto.ManualChargeRequest;
import com.fuyun.billing.dto.QuoteRequest;
import com.fuyun.billing.entity.ChargeItem;
import com.fuyun.billing.entity.ChargeItemComponent;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.enums.ChargeSource;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.enums.ItemClass;
import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.enums.VisitType;
import com.fuyun.billing.internal.BillingDomainEvent;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.record.PriceSnapshot;
import com.fuyun.billing.service.IChargeItemService;
import com.fuyun.billing.service.IChargePriceService;
import com.fuyun.billing.vo.QuoteVO;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SimpleTimeZone;
import java.util.TimeZone;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** 计价引擎单测（资金红线：金额服务端算、快照冻结、billing_key 防重、事件登记）。 */
@ExtendWith(MockitoExtension.class)
class PricingEngineServiceImplTest {

    @Mock
    private IChargeItemService itemService;

    @Mock
    private IChargePriceService priceService;

    @Mock
    private FeeRecordMapper feeRecordMapper;

    @Mock
    private ApplicationEventPublisher events;

    private PricingEngineServiceImpl engine;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FeeRecord.class);
    }

    @BeforeEach
    void setUp() {
        engine = new PricingEngineServiceImpl(itemService, priceService, events);
        ReflectionTestUtils.setField(engine, "baseMapper", feeRecordMapper);
        ReflectionTestUtils.setField(engine, "entityClass", FeeRecord.class);
    }

    private ChargeItem item(long id, String code) {
        ChargeItem it = new ChargeItem();
        it.setId(id);
        it.setItemCode(code);
        it.setItemName("检查费");
        it.setItemClass(ItemClass.TREATMENT);
        it.setFeeCategory("EXAM_FEE");
        it.setComboFlag(false);
        // 生效状态必置：预计价批查补偿校验在引擎侧判 ACTIVE（缺行/停用区分），夹具须为生效项目
        it.setStatus(ItemStatus.ACTIVE);
        return it;
    }

    private PriceSnapshot snap(long itemId, long price, int ver) {
        return new PriceSnapshot(itemId, price, ver, "NHSA1", "2026Q3", new BigDecimal("0.1"), 9999L);
    }

    private ChargeItemComponent component(long comboItemId, long memberId, String defaultQuantity) {
        ChargeItemComponent c = new ChargeItemComponent();
        c.setComboItemId(comboItemId);
        c.setComponentItemId(memberId);
        c.setDefaultQuantity(new BigDecimal(defaultQuantity));
        return c;
    }

    /** 预计价单行夹具（数量恒 1：补偿校验分支用例不涉金额算式，聚焦错误语义等价）。 */
    private QuoteRequest.Line line(String itemCode) {
        return new QuoteRequest.Line(itemCode, BigDecimal.ONE);
    }

    /** 预计价请求夹具（补偿校验用例统一就诊/患者上下文，行序即入参序）。 */
    private QuoteRequest quoteReq(QuoteRequest.Line... lines) {
        return new QuoteRequest(7L, "O2026091700001", List.of(lines));
    }

    @Test
    @DisplayName("正常计费：金额=单价×数量服务端算、快照冻结、状态 PENDING、发 fee.created")
    void generatePersistsFrozenPendingFeeAndPublishs() {
        when(itemService.requireActiveByCode("C001")).thenReturn(item(100L, "C001"));
        when(priceService.snapshot("C001", 100L)).thenReturn(snap(100L, 2000L, 2));
        // 模拟 MP ASSIGN_ID 回填：insert 时给实体置 id（否则 save 后 fee.getId() 为 null，long 拆箱 NPE）
        when(feeRecordMapper.insert(any(FeeRecord.class))).thenAnswer(inv -> {
            inv.getArgument(0, FeeRecord.class).setId(123L);
            return 1;
        });

        long feeId = engine.generateFromSource(new FeeGenerateCommand(
                7L,
                "O2026091700001",
                ChargeSource.ORDER_LINKED,
                "ORD-01",
                TriggerType.ORDER_CONFIRMED,
                "C001",
                new BigDecimal("3"),
                VisitType.OUT,
                null,
                null));

        ArgumentCaptor<FeeRecord> captor = ArgumentCaptor.forClass(FeeRecord.class);
        verify(feeRecordMapper).insert(captor.capture());
        FeeRecord row = captor.getValue();
        assertThat(row.getAmount()).isEqualTo(6000L); // 2000×3 服务端算，不采信入参金额
        assertThat(row.getUnitPriceSnapshot()).isEqualTo(2000L);
        assertThat(row.getPriceVersion()).isEqualTo(2);
        assertThat(row.getNhsaCodeSnapshot()).isEqualTo("NHSA1");
        assertThat(row.getStatus()).isEqualTo(FeeStatus.PENDING);
        // 事件断言走冻结机制：事务内应用事件（AFTER_COMMIT 出 MQ 归 BillingEventPublisher，Task 5/10 锁定）
        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(evt.capture());
        BillingDomainEvent published = (BillingDomainEvent) evt.getValue();
        assertThat(published.eventType()).isEqualTo("billing.fee.created");
        assertThat(published.payload()).isInstanceOf(FeeCreatedPayload.class);
        assertThat(feeId).isEqualTo(123L); // insert 回填 id 原样返回（ASSIGN_ID 语义）
    }

    // ===== 时区纪律专项 A 类：计费日落库北京钟面锚 =====

    @Test
    @DisplayName("计费日时区锚：默认时区与北京日期分歧时，落库计费日仍取北京钟面（billing_key 防重段同源）")
    void generateBindsBillingDateToBeijingClockUnderDivergedDefaultZone() {
        when(itemService.requireActiveByCode("C001")).thenReturn(item(100L, "C001"));
        when(priceService.snapshot("C001", 100L)).thenReturn(snap(100L, 2000L, 2));
        // 模拟 MP ASSIGN_ID 回填：insert 时给实体置 id（否则 save 后 fee.getId() 为 null，long 拆箱 NPE）
        when(feeRecordMapper.insert(any(FeeRecord.class))).thenAnswer(inv -> {
            inv.getArgument(0, FeeRecord.class).setId(123L);
            return 1;
        });

        TimeZone original = TimeZone.getDefault();
        try {
            // 构造与北京当前日期必然分歧的默认时区：-12h/+14h 二选一（两分歧窗北京钟面 [00:00,20:00)
            // 与 [18:00,24:00) 并集覆盖全天）——BUG-03 先例 setDefault(UTC) 每日 16h 重合窗内对缺陷
            // 代码也绿，本构造任意时刻可复现「非北京时区 JVM 取错医疗日」。时区 ID 必须取偏移字面量
            // （如 -12:00）：java.time 解析不了任意自定义 ID，否则 LocalDate.now() 抛 ZoneRulesException
            Instant now = Instant.now();
            ZoneId beijing = ZoneId.of("Asia/Shanghai");
            int divergeMillis = -12 * 3600_000;
            if (now.atZone(beijing)
                    .toLocalDate()
                    .equals(now.atZone(ZoneOffset.ofTotalSeconds(divergeMillis / 1000))
                            .toLocalDate())) {
                divergeMillis = 14 * 3600_000; // -12h 与北京同日时改用 +14h（引理保证必分歧）
            }
            TimeZone.setDefault(new SimpleTimeZone(
                    divergeMillis,
                    ZoneOffset.ofTotalSeconds(divergeMillis / 1000).getId()));

            engine.generateFromSource(new FeeGenerateCommand(
                    7L,
                    "O2026091700001",
                    ChargeSource.ORDER_LINKED,
                    "ORD-01",
                    TriggerType.ORDER_CONFIRMED,
                    "C001",
                    BigDecimal.ONE,
                    VisitType.OUT,
                    null,
                    null));

            // 断言对象=服务端计算并落库的计费日（mapper captor 捕获，期望按北京钟面推导禁裸 now()）；
            // 缺陷实现（裸 LocalDate.now()）在分歧默认时区下取容器日期，billing_date 与防重键第五段
            // 同漂移——对北京今日断言即红
            ArgumentCaptor<FeeRecord> captor = ArgumentCaptor.forClass(FeeRecord.class);
            verify(feeRecordMapper).insert(captor.capture());
            assertThat(captor.getValue().getBillingDate()).isEqualTo(LocalDate.now(beijing));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("重复计费拦截：billing_key 查重命中抛 BILL-1009 不落库不发事件")
    void duplicateBillingKeyIsBlocked() {
        when(itemService.requireActiveByCode("C001")).thenReturn(item(100L, "C001"));
        when(priceService.snapshot("C001", 100L)).thenReturn(snap(100L, 2000L, 2));
        // 查重经 lambdaQuery().exists()（MP 3.5.17 链式 exists→count，由 baseMapper.selectCount 承载），命中即拒
        when(feeRecordMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> engine.generateFromSource(new FeeGenerateCommand(
                        7L,
                        "O2026091700001",
                        ChargeSource.ORDER_LINKED,
                        "ORD-01",
                        TriggerType.ORDER_CONFIRMED,
                        "C001",
                        BigDecimal.ONE,
                        VisitType.OUT,
                        null,
                        null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.DUPLICATE_CHARGING));
        verify(feeRecordMapper, never()).insert(any(FeeRecord.class));
        // 查重 SQL 守卫钉死：等值条件必须落在 billing_key 列且携带五段拼装键（患者维度入键防手工通道跨患者误拦）
        ArgumentCaptor<Wrapper<FeeRecord>> keyCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(feeRecordMapper).selectCount(keyCaptor.capture());
        LambdaQueryWrapper<FeeRecord> keyWrapper = (LambdaQueryWrapper<FeeRecord>) keyCaptor.getValue();
        assertThat(keyWrapper.getSqlSegment()).contains("billing_key");
        assertThat(keyWrapper.getParamNameValuePairs().values())
                .anyMatch(v -> String.valueOf(v).contains("7|ORD-01|ORDER_CONFIRMED|100|"));
    }

    @Test
    @DisplayName("visit_id 结构守卫：非法形态计费落库前 BILL-1013 拒（CF-3，事件消费与手工计费共同入口）")
    void generateRejectsMalformedVisitIdAsBill1013() {
        assertThatThrownBy(() -> engine.generateFromSource(new FeeGenerateCommand(
                        7L,
                        "X123",
                        ChargeSource.ORDER_LINKED,
                        "ORD-01",
                        TriggerType.ORDER_CONFIRMED,
                        "C001",
                        BigDecimal.ONE,
                        VisitType.OUT,
                        null,
                        null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.VISIT_ID_MALFORMED));
        // 守卫先于一切数据访问：非法结构不落库、不查项目（mapper 零交互）
        verifyNoInteractions(feeRecordMapper, itemService);
    }

    @Test
    @DisplayName("手工计费缺操作者/理由：BILL-1012 拒绝（红线 3）")
    void manualChargeWithoutOperatorReasonBlocked() {
        when(itemService.requireActiveByCode("C001")).thenReturn(item(100L, "C001"));
        when(priceService.snapshot("C001", 100L)).thenReturn(snap(100L, 2000L, 2));
        when(feeRecordMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> engine.generateFromSource(new FeeGenerateCommand(
                        7L,
                        "O2026091700001",
                        ChargeSource.MANUAL,
                        "9527",
                        TriggerType.MANUAL,
                        "C001",
                        BigDecimal.ONE,
                        VisitType.OUT,
                        null,
                        null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING));
    }

    @Test
    @DisplayName("预计价：不落库不发消息，仅返回金额与对照口径")
    void quoteDoesNotPersistOrPublish() {
        when(itemService.listByCodes(Set.of("C001"))).thenReturn(Map.of("C001", item(100L, "C001")));
        when(priceService.snapshots(Set.of(100L))).thenReturn(Map.of(100L, snap(100L, 1500L, 1)));

        var vo = engine.quote(
                new QuoteRequest(7L, "O2026091700001", List.of(new QuoteRequest.Line("C001", new BigDecimal("2")))));

        assertThat(vo.totalAmount()).isEqualTo(3000L);
        verify(feeRecordMapper, never()).insert(any(FeeRecord.class));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("组合项目计费展开：逐成员一行、quantity=入参×构成默认量、逐行发 fee.created")
    void comboItemExpandsIntoMemberFeesOnGenerate() {
        ChargeItem combo = item(100L, "C-COMBO");
        combo.setComboFlag(true);
        when(itemService.requireActiveByCode("C-COMBO")).thenReturn(combo);
        ChargeItem m1 = item(101L, "M1");
        ChargeItem m2 = item(102L, "M2");
        when(itemService.listComponents(100L))
                .thenReturn(List.of(component(100L, 101L, "0.5"), component(100L, 102L, "2")));
        when(itemService.getById(101L)).thenReturn(m1);
        when(itemService.getById(102L)).thenReturn(m2);
        when(itemService.requireActiveByCode("M1")).thenReturn(m1);
        when(itemService.requireActiveByCode("M2")).thenReturn(m2);
        when(priceService.snapshot("M1", 101L)).thenReturn(snap(101L, 1000L, 1));
        when(priceService.snapshot("M2", 102L)).thenReturn(snap(102L, 500L, 1));
        when(feeRecordMapper.insert(any(FeeRecord.class))).thenAnswer(inv -> {
            inv.getArgument(0, FeeRecord.class).setId(500L);
            return 1;
        });

        long firstFeeId = engine.generateFromSource(new FeeGenerateCommand(
                7L,
                "O2026091700001",
                ChargeSource.ORDER_LINKED,
                "ORD-C",
                TriggerType.ORDER_CONFIRMED,
                "C-COMBO",
                new BigDecimal("2"),
                VisitType.OUT,
                null,
                null));

        assertThat(firstFeeId).isEqualTo(500L); // 首条成员行 id 作命令回执
        ArgumentCaptor<FeeRecord> captor = ArgumentCaptor.forClass(FeeRecord.class);
        verify(feeRecordMapper, times(2)).insert(captor.capture());
        List<FeeRecord> rows = captor.getAllValues();
        assertThat(rows.get(0).getChargeItemId()).isEqualTo(101L);
        assertThat(rows.get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("1.0")); // 2×0.5
        assertThat(rows.get(0).getAmount()).isEqualTo(1000L); // 1000×1.0 服务端算
        assertThat(rows.get(1).getQuantity()).isEqualByComparingTo(new BigDecimal("4")); // 2×2
        assertThat(rows.get(1).getAmount()).isEqualTo(2000L); // 500×4
        verify(events, times(2)).publishEvent(any(Object.class)); // 成员行各自发布 fee.created
    }

    @Test
    @DisplayName("组合项目未维护构成：BILL-1008 定价不可得显式拒（禁静默零费用）")
    void comboWithoutComponentsFailsAsBill1008() {
        ChargeItem combo = item(100L, "C-COMBO");
        combo.setComboFlag(true);
        when(itemService.requireActiveByCode("C-COMBO")).thenReturn(combo);
        when(itemService.listComponents(100L)).thenReturn(List.of());

        assertThatThrownBy(() -> engine.generateFromSource(new FeeGenerateCommand(
                        7L,
                        "O2026091700001",
                        ChargeSource.ORDER_LINKED,
                        "ORD-C",
                        TriggerType.ORDER_CONFIRMED,
                        "C-COMBO",
                        BigDecimal.ONE,
                        VisitType.OUT,
                        null,
                        null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.PRICING_UNAVAILABLE));
        verify(feeRecordMapper, never()).insert(any(FeeRecord.class));
    }

    @Test
    @DisplayName("组合成员缺行：BILL-1001 显式暴露构成脏数据（禁空引用静默 NPE）")
    void comboMemberMissingFailsAsBill1001() {
        ChargeItem combo = item(100L, "C-COMBO");
        combo.setComboFlag(true);
        when(itemService.requireActiveByCode("C-COMBO")).thenReturn(combo);
        when(itemService.listComponents(100L)).thenReturn(List.of(component(100L, 101L, "1")));
        when(itemService.getById(101L)).thenReturn(null);

        assertThatThrownBy(() -> engine.generateFromSource(new FeeGenerateCommand(
                        7L,
                        "O2026091700001",
                        ChargeSource.ORDER_LINKED,
                        "ORD-C",
                        TriggerType.ORDER_CONFIRMED,
                        "C-COMBO",
                        BigDecimal.ONE,
                        VisitType.OUT,
                        null,
                        null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.CHARGE_ITEM_NOT_FOUND));
    }

    @Test
    @DisplayName("组合预计价与实收同源展开：逐成员行合计，不落库不发消息")
    void comboQuoteSumsMemberLines() {
        ChargeItem combo = item(100L, "C-COMBO");
        combo.setComboFlag(true);
        ChargeItem m1 = item(101L, "M1");
        ChargeItem m2 = item(102L, "M2");
        when(itemService.listByCodes(Set.of("C-COMBO"))).thenReturn(Map.of("C-COMBO", combo));
        when(itemService.listComponentsByComboItemIds(Set.of(100L)))
                .thenReturn(Map.of(100L, List.of(component(100L, 101L, "1"), component(100L, 102L, "3"))));
        when(itemService.listByIds(Set.of(101L, 102L))).thenReturn(List.of(m1, m2));
        when(priceService.snapshots(Set.of(101L, 102L)))
                .thenReturn(Map.of(101L, snap(101L, 1500L, 1), 102L, snap(102L, 1000L, 1)));

        var vo = engine.quote(
                new QuoteRequest(7L, "O2026091700001", List.of(new QuoteRequest.Line("C-COMBO", new BigDecimal("2")))));

        assertThat(vo.lines()).hasSize(2); // 展开两成员行
        assertThat(vo.totalAmount()).isEqualTo(3000L + 6000L); // 1500×(2×1) + 1000×(2×3)
        verify(feeRecordMapper, never()).insert(any(FeeRecord.class));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("组合预计价未维护构成：与实收同源 BILL-1008 显式拒（第 2 轮审查 P2-5，禁静默空预览）")
    void quoteRejectsComboWithoutComponentsAsBill1008() {
        ChargeItem combo = item(100L, "C-COMBO");
        combo.setComboFlag(true);
        when(itemService.listByCodes(Set.of("C-COMBO"))).thenReturn(Map.of("C-COMBO", combo));
        when(itemService.listComponentsByComboItemIds(Set.of(100L))).thenReturn(Map.of());

        assertThatThrownBy(() -> engine.quote(new QuoteRequest(
                        7L, "O2026091700001", List.of(new QuoteRequest.Line("C-COMBO", BigDecimal.ONE)))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.PRICING_UNAVAILABLE));
    }

    @Test
    @DisplayName("预计价批查接线（A.4.3-14）：多行单据四类批查各恰一次、键集去重收敛，逐行单查链零触达")
    void quoteBatchesAllKeySetLookupsOnceWithoutPerLineSingleQueries() {
        // 单据形态：直行 C001（重复两行验证键集去重、独立成行）+ 组合 C-COMBO（展开 M1/M2）+ 直行 C002
        ChargeItem direct1 = item(200L, "C001");
        ChargeItem direct2 = item(201L, "C002");
        ChargeItem combo = item(100L, "C-COMBO");
        combo.setComboFlag(true);
        ChargeItem m1 = item(101L, "M1");
        ChargeItem m2 = item(102L, "M2");
        when(itemService.listByCodes(any())).thenReturn(Map.of("C001", direct1, "C002", direct2, "C-COMBO", combo));
        when(itemService.listComponentsByComboItemIds(any()))
                .thenReturn(Map.of(100L, List.of(component(100L, 101L, "1"), component(100L, 102L, "3"))));
        when(itemService.listByIds(any())).thenReturn(List.of(m1, m2));
        when(priceService.snapshots(any()))
                .thenReturn(Map.of(
                        200L, snap(200L, 1500L, 1),
                        201L, snap(201L, 200L, 1),
                        101L, snap(101L, 1500L, 1),
                        102L, snap(102L, 1000L, 1)));

        var vo = engine.quote(new QuoteRequest(
                7L,
                "O2026091700001",
                List.of(
                        new QuoteRequest.Line("C001", new BigDecimal("2")),
                        new QuoteRequest.Line("C-COMBO", new BigDecimal("2")),
                        new QuoteRequest.Line("C002", new BigDecimal("3")),
                        new QuoteRequest.Line("C001", BigDecimal.ONE))));

        // 金额逐字段等价（与逐行单查同算式）：1500×2 + M1 1500×(2×1) + M2 1000×(2×3) + 200×3 + 1500×1
        assertThat(vo.totalAmount()).isEqualTo(3000L + 3000L + 6000L + 600L + 1500L);
        assertThat(vo.lines())
                .extracting(
                        QuoteVO.QuoteLine::itemId,
                        QuoteVO.QuoteLine::itemCode,
                        QuoteVO.QuoteLine::unitPrice,
                        QuoteVO.QuoteLine::quantity,
                        QuoteVO.QuoteLine::amount,
                        QuoteVO.QuoteLine::selfExpenseOnly)
                .containsExactly(
                        tuple(200L, "C001", 1500L, new BigDecimal("2"), 3000L, false),
                        tuple(101L, "M1", 1500L, new BigDecimal("2"), 3000L, false),
                        tuple(102L, "M2", 1000L, new BigDecimal("6"), 6000L, false),
                        tuple(201L, "C002", 200L, new BigDecimal("3"), 600L, false),
                        tuple(200L, "C001", 1500L, BigDecimal.ONE, 1500L, false));
        // 四类批查各恰一次 + 键集钉死（重复码去重；组合本体 id 不进快照键集）
        ArgumentCaptor<Collection<String>> codesCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(itemService, times(1)).listByCodes(codesCaptor.capture());
        assertThat(codesCaptor.getValue()).containsExactlyInAnyOrder("C001", "C002", "C-COMBO");
        ArgumentCaptor<Collection<Long>> comboIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(itemService, times(1)).listComponentsByComboItemIds(comboIdsCaptor.capture());
        assertThat(comboIdsCaptor.getValue()).containsExactly(100L);
        ArgumentCaptor<Collection<Long>> memberIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(itemService, times(1)).listByIds(memberIdsCaptor.capture());
        assertThat(memberIdsCaptor.getValue()).containsExactlyInAnyOrder(101L, 102L);
        ArgumentCaptor<Collection<Long>> pricingIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(priceService, times(1)).snapshots(pricingIdsCaptor.capture());
        assertThat(pricingIdsCaptor.getValue()).containsExactlyInAnyOrder(200L, 201L, 101L, 102L);
        // 逐行单查链零触达（OPT-04 灶位根除锚：单查面仅供实收链 generateFromSource 消费）
        verify(itemService, never()).requireActiveByCode(any());
        verify(itemService, never()).listComponents(anyLong());
        verify(itemService, never()).getById(any());
        verify(priceService, never()).snapshot(any(), anyLong());
    }

    @Test
    @DisplayName("无组合单据：仅编码批查+快照批查触达，构成/成员批查零调用（恒 3 查语义）")
    void quoteWithoutComboSkipsComponentAndMemberBatches() {
        when(itemService.listByCodes(Set.of("C001"))).thenReturn(Map.of("C001", item(200L, "C001")));
        when(priceService.snapshots(Set.of(200L))).thenReturn(Map.of(200L, snap(200L, 1500L, 1)));

        var vo = engine.quote(quoteReq(new QuoteRequest.Line("C001", new BigDecimal("2"))));

        assertThat(vo.totalAmount()).isEqualTo(3000L);
        verify(itemService, never()).listComponentsByComboItemIds(any());
        verify(itemService, never()).listByIds(any());
    }

    @Test
    @DisplayName("补偿校验分支等价：缺行/停用/未维护构成/无生效价格与逐行单查同码同文案同 HTTP 态，行序不变")
    void quoteCompensatingValidationMatchesSingleQueryErrorSemantics() {
        ChargeItem active = item(200L, "C-ACTIVE");
        ChargeItem inactive = item(202L, "C-INACTIVE");
        inactive.setStatus(ItemStatus.INACTIVE);
        ChargeItem combo = item(100L, "C-COMBO");
        combo.setComboFlag(true);
        // 行序不变锚：有效行在前，缺行（第 2 行）先于停用（第 3 行）拒——首个无效行报错与逐行单查同序
        //   （有效行须取价成功，故快照批查先备好 C-ACTIVE 的版本）
        when(itemService.listByCodes(Set.of("C-ACTIVE", "C-MISSING", "C-INACTIVE")))
                .thenReturn(Map.of("C-ACTIVE", active, "C-INACTIVE", inactive));
        when(priceService.snapshots(any())).thenReturn(Map.of(200L, snap(200L, 1500L, 1)));
        assertThatThrownBy(() -> engine.quote(quoteReq(line("C-ACTIVE"), line("C-MISSING"), line("C-INACTIVE"))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.CHARGE_ITEM_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("收费项目不存在：C-MISSING");
                });
        // 缺行分支（单行形态）：文案与单查 requireActiveByCode 逐字一致（BILL-1001/404）
        when(itemService.listByCodes(Set.of("C-MISSING"))).thenReturn(Map.of());
        assertThatThrownBy(() -> engine.quote(quoteReq(line("C-MISSING"))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.CHARGE_ITEM_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("收费项目不存在：C-MISSING");
                });
        // 停用分支：文案与单查 requireActiveByCode 逐字一致（BILL-1003/409）
        when(itemService.listByCodes(Set.of("C-INACTIVE"))).thenReturn(Map.of("C-INACTIVE", inactive));
        assertThatThrownBy(() -> engine.quote(quoteReq(line("C-INACTIVE"))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.CHARGE_ITEM_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("收费项目已停用：C-INACTIVE");
                });
        // 组合未维护构成分支：文案与 quote 现状逐字一致（BILL-1008/409，未维护构成不出键）
        when(itemService.listByCodes(Set.of("C-COMBO"))).thenReturn(Map.of("C-COMBO", combo));
        when(itemService.listComponentsByComboItemIds(Set.of(100L))).thenReturn(Map.of());
        assertThatThrownBy(() -> engine.quote(quoteReq(line("C-COMBO"))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PRICING_UNAVAILABLE);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("组合项目未维护构成：C-COMBO");
                });
        // 无生效价格分支：文案与单查 snapshot 逐字一致（BILL-1008/409，无版本项目不出键不抛错）
        when(itemService.listByCodes(Set.of("C-ACTIVE"))).thenReturn(Map.of("C-ACTIVE", active));
        when(priceService.snapshots(any())).thenReturn(Map.of());
        assertThatThrownBy(() -> engine.quote(quoteReq(line("C-ACTIVE"))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PRICING_UNAVAILABLE);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("项目无生效价格版本：C-ACTIVE");
                });
    }

    @Test
    @DisplayName("组合成员补偿校验等价：成员缺行/停用与单查 memberItem 同码同文案，成员先取价后校验下一成员")
    void quoteMemberCompensatingValidationMatchesSingleQueryErrors() {
        ChargeItem combo = item(100L, "C-COMBO");
        combo.setComboFlag(true);
        ChargeItem m1 = item(101L, "M1"); // 场景一：有效但无生效价格（先于 M2 缺行校验暴露）
        ChargeItem m3 = item(103L, "M3"); // 场景三：停用
        m3.setStatus(ItemStatus.INACTIVE);
        when(itemService.listByCodes(Set.of("C-COMBO"))).thenReturn(Map.of("C-COMBO", combo));
        // 同一桩位三次变参依次命中（场景序即断言序）：构成批查 / 成员批查 / 快照批查各三次
        when(itemService.listComponentsByComboItemIds(Set.of(100L)))
                .thenReturn(
                        Map.of(100L, List.of(component(100L, 101L, "1"), component(100L, 102L, "1"))),
                        Map.of(100L, List.of(component(100L, 104L, "1"))),
                        Map.of(100L, List.of(component(100L, 103L, "1"))));
        when(itemService.listByIds(any())).thenReturn(List.of(m1), List.of(), List.of(m3));
        when(priceService.snapshots(any())).thenReturn(Map.of());

        // 场景一（序等价锚）：M1 取价失败先拒，先于 M2 缺行校验——与逐行单查「逐成员先取价再校验下一成员」同序
        assertThatThrownBy(() -> engine.quote(quoteReq(line("C-COMBO"))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PRICING_UNAVAILABLE);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("项目无生效价格版本：M1");
                });
        // 场景二：成员缺行（构成脏数据）文案与单查 memberItem 逐字一致（BILL-1001/404）
        assertThatThrownBy(() -> engine.quote(quoteReq(line("C-COMBO"))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.CHARGE_ITEM_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("组合成员项目不存在（构成脏数据）：104");
                });
        // 场景三：成员停用走单查 requireActiveByCode 停用文案（BILL-1003/409）
        assertThatThrownBy(() -> engine.quote(quoteReq(line("C-COMBO"))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.CHARGE_ITEM_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("收费项目已停用：M3");
                });
    }

    @Test
    @DisplayName("费用作废：PENDING 条件更新置 CANCELLED，行保留不物理删（billing_key 经部分索引释放）")
    void cancelTransitionsPendingToCancelled() {
        FeeRecord fee = new FeeRecord();
        fee.setId(1L);
        fee.setStatus(FeeStatus.PENDING);
        when(feeRecordMapper.selectById(1L)).thenReturn(fee);
        when(feeRecordMapper.update(isNull(), any())).thenReturn(1);

        engine.cancel(1L, "开错项目当日更正");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<FeeRecord>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(feeRecordMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<FeeRecord> wrapper = (LambdaUpdateWrapper<FeeRecord>) captor.getValue();
        assertThat(wrapper.getSqlSet()).contains("status");
        assertThat(wrapper.getParamNameValuePairs().containsValue(FeeStatus.CANCELLED))
                .isTrue();
        // 行逻辑保留：禁物理删、禁 updateById 全量回写（D-13 同型红线）
        verify(feeRecordMapper, never()).deleteById(anyLong());
        verify(feeRecordMapper, never()).updateById(any(FeeRecord.class));
    }

    @Test
    @DisplayName("已结算费用作废：前置守卫 BILL-1011，不发条件更新")
    void cancelRejectsSettledFeeAsBill1011() {
        FeeRecord fee = new FeeRecord();
        fee.setId(1L);
        fee.setStatus(FeeStatus.SETTLED);
        when(feeRecordMapper.selectById(1L)).thenReturn(fee);

        assertThatThrownBy(() -> engine.cancel(1L, "跨日误退请求"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.FEE_STATE_NOT_ALLOWED));
        verify(feeRecordMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("缺行作废：BILL-1010 显式拒（404，禁静默成功）")
    void cancelRejectsMissingFeeAsBill1010() {
        when(feeRecordMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> engine.cancel(1L, "任意"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.FEE_NOT_FOUND));
    }

    @Test
    @DisplayName("作废竞态：读-写窗口被并发结算，条件更新 0 行命中拒 BILL-1011")
    void cancelRaceLostRejectsBill1011() {
        FeeRecord fee = new FeeRecord();
        fee.setId(1L);
        fee.setStatus(FeeStatus.PENDING);
        when(feeRecordMapper.selectById(1L)).thenReturn(fee);
        when(feeRecordMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> engine.cancel(1L, "并发竞态验证"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.FEE_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("手工计费：命令按登录上下文组装（MANUAL/工号 sourceRef）并委托统一生成入口")
    void manualChargeAssemblesCommandAndDelegates() {
        OperatorContextHolder.set("9527");
        try {
            when(itemService.requireActiveByCode("C001")).thenReturn(item(100L, "C001"));
            when(priceService.snapshot("C001", 100L)).thenReturn(snap(100L, 2000L, 2));
            when(feeRecordMapper.selectCount(any())).thenReturn(0L);
            when(feeRecordMapper.insert(any(FeeRecord.class))).thenAnswer(inv -> {
                inv.getArgument(0, FeeRecord.class).setId(77L);
                return 1;
            });

            long feeId = engine.manualCharge(
                    new ManualChargeRequest(7L, "O2026091700001", "C001", new BigDecimal("1"), "患者补收治疗费"));

            assertThat(feeId).isEqualTo(77L);
            ArgumentCaptor<FeeRecord> captor = ArgumentCaptor.forClass(FeeRecord.class);
            verify(feeRecordMapper).insert(captor.capture());
            FeeRecord row = captor.getValue();
            assertThat(row.getChargeSource()).isEqualTo(ChargeSource.MANUAL);
            assertThat(row.getSourceRef()).isEqualTo("9527"); // 红线 3：操作者=登录上下文，不由前端传
            assertThat(row.getOperator()).isEqualTo("9527");
            assertThat(row.getManualReason()).isEqualTo("患者补收治疗费");
        } finally {
            OperatorContextHolder.clear();
        }
    }

    @Test
    @DisplayName("手工计费无登录上下文：BILL-1012 前置拒且零落库（身份红线）")
    void manualChargeRejectsWithoutLoginContext() {
        assertThatThrownBy(() -> engine.manualCharge(
                        new ManualChargeRequest(7L, "O2026091700001", "C001", BigDecimal.ONE, "无登录态补录")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING));
        verify(feeRecordMapper, never()).insert(any(FeeRecord.class));
    }

    @Test
    @DisplayName("未结算费用清单：按就诊号+PENDING 状态过滤（Task 13 结算勾稽入口，wrapper 守卫钉死）")
    void pendingFeesFiltersByVisitAndPendingStatus() {
        FeeRecord row = new FeeRecord();
        row.setId(11L);
        when(feeRecordMapper.selectList(any())).thenReturn(List.of(row));

        assertThat(engine.pendingFees("O2026091700001")).containsExactly(row);

        // 清单查询 SQL 守卫钉死：等值条件必须落在 visit_id+status 列且携带本次就诊号与 PENDING（防全表拉取）
        ArgumentCaptor<Wrapper<FeeRecord>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(feeRecordMapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<FeeRecord> wrapper = (LambdaQueryWrapper<FeeRecord>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("visit_id").contains("status");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("O2026091700001", FeeStatus.PENDING);
    }

    @Test
    @DisplayName("费用查询分页：按就诊号全状态过滤、id 升序稳定排序（行序=事件行序，wrapper 守卫钉死）")
    void pageByVisitFiltersByVisitAndOrdersById() {
        FeeRecord row = new FeeRecord();
        row.setId(9L);
        when(feeRecordMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<FeeRecord> page = inv.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1);
            return page;
        });

        PageResult<FeeRecord> result = engine.pageByVisit("O2026091700001", 0, 20);

        assertThat(result.page()).isZero(); // 0 基分页契约原样回显
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.content()).hasSize(1);
        // 费用查询 SQL 守卫钉死：等值条件落在 visit_id 列且排序为 id 升序（A.4.3-17 唯一顺序约束）
        ArgumentCaptor<Wrapper<FeeRecord>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(feeRecordMapper).selectPage(any(), wrapperCaptor.capture());
        LambdaQueryWrapper<FeeRecord> wrapper = (LambdaQueryWrapper<FeeRecord>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("visit_id").containsIgnoringCase("ORDER BY");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("O2026091700001");
    }
}
