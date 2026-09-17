package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.enums.VisitType;
import com.fuyun.billing.internal.BillingDomainEvent;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.record.PriceSnapshot;
import com.fuyun.billing.service.IChargeItemService;
import com.fuyun.billing.service.IChargePriceService;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import java.math.BigDecimal;
import java.util.List;
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
        when(itemService.requireActiveByCode("C001")).thenReturn(item(100L, "C001"));
        when(priceService.snapshot("C001", 100L)).thenReturn(snap(100L, 1500L, 1));

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
        when(itemService.requireActiveByCode("C-COMBO")).thenReturn(combo);
        ChargeItem m1 = item(101L, "M1");
        ChargeItem m2 = item(102L, "M2");
        when(itemService.listComponents(100L))
                .thenReturn(List.of(component(100L, 101L, "1"), component(100L, 102L, "3")));
        when(itemService.getById(101L)).thenReturn(m1);
        when(itemService.getById(102L)).thenReturn(m2);
        when(itemService.requireActiveByCode("M1")).thenReturn(m1);
        when(itemService.requireActiveByCode("M2")).thenReturn(m2);
        when(priceService.snapshot("M1", 101L)).thenReturn(snap(101L, 1500L, 1));
        when(priceService.snapshot("M2", 102L)).thenReturn(snap(102L, 1000L, 1));

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
        when(itemService.requireActiveByCode("C-COMBO")).thenReturn(combo);
        when(itemService.listComponents(100L)).thenReturn(List.of());

        assertThatThrownBy(() -> engine.quote(new QuoteRequest(
                        7L, "O2026091700001", List.of(new QuoteRequest.Line("C-COMBO", BigDecimal.ONE)))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.PRICING_UNAVAILABLE));
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
