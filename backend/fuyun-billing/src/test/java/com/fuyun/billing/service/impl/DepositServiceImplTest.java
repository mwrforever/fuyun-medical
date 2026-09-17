package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.api.DepositChangedPayload;
import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.billing.dto.DepositRequest;
import com.fuyun.billing.entity.DepositAccount;
import com.fuyun.billing.entity.DepositTxn;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.enums.DepositStatus;
import com.fuyun.billing.enums.DepositTxnStatus;
import com.fuyun.billing.enums.DepositTxnType;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.enums.PaymentMethod;
import com.fuyun.billing.internal.BillingDomainEvent;
import com.fuyun.billing.mapper.DepositAccountMapper;
import com.fuyun.billing.mapper.DepositTxnMapper;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.properties.BillingProperties;
import com.fuyun.billing.vo.DepositAccountVO;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 押金服务单测（资金红线：mutateBalance 原子记账回读、欠费阈值 NORMAL⇄ARREARS 切换且状态迁移
 * 仅 SET status（getSqlSet 守卫禁 stale balance 回写）、终态拒缴存 BILL-1023、deposit.changed
 * 事件载荷冻结、查询 wrapper SQL 守卫钉死）。
 */
@ExtendWith(MockitoExtension.class)
class DepositServiceImplTest {

    /** 住院就诊号夹具（I 前缀 → 押金业务唯一受理形态，门诊预交金按国家政策不设账户） */
    private static final String VISIT = "I2026091600001";

    /** 欠费预警阈值夹具（分）：100 元 */
    private static final long THRESHOLD = 10000L;

    @Mock
    private DepositAccountMapper depositAccountMapper;

    @Mock
    private DepositTxnMapper depositTxnMapper;

    @Mock
    private FeeRecordMapper feeRecordMapper;

    @Mock
    private ApplicationEventPublisher events;

    private DepositServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DepositAccount.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DepositTxn.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FeeRecord.class);
    }

    @BeforeEach
    void setUp() {
        // 阈值参数直构（无 Spring 上下文；Bean 注册归 Task 16 config 装配）
        service = new DepositServiceImpl(depositTxnMapper, feeRecordMapper, events, new BillingProperties(THRESHOLD));
        ReflectionTestUtils.setField(service, "baseMapper", depositAccountMapper);
        ReflectionTestUtils.setField(service, "entityClass", DepositAccount.class);
        OperatorContextHolder.set("E1001");
    }

    /** 模拟 MP ASSIGN_ID 回填：流水 insert 时给实体置 id（SettlementServiceImplTest 同款夹具） */
    private void stubTxnInsertBackfillsId() {
        when(depositTxnMapper.insert(any(DepositTxn.class))).thenAnswer(inv -> {
            inv.getArgument(0, DepositTxn.class).setId(9001L);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        OperatorContextHolder.clear();
    }

    private DepositAccount account(long id, long balance, long threshold, DepositStatus status) {
        DepositAccount acc = new DepositAccount();
        acc.setId(id);
        acc.setPatientId(7L);
        acc.setVisitId(VISIT);
        acc.setBalance(balance);
        acc.setWarningThreshold(threshold);
        acc.setStatus(status);
        return acc;
    }

    private FeeRecord confirmedFee(long id, long amount) {
        FeeRecord fee = new FeeRecord();
        fee.setId(id);
        fee.setVisitId(VISIT);
        fee.setAmount(amount);
        fee.setStatus(FeeStatus.CONFIRMED);
        return fee;
    }

    @Test
    @DisplayName("缴存记账：mutateBalance 原子回读新余额、流水 ACTIVE 落库、状态维持 NORMAL、发 deposit.changed")
    void depositMutatesBalanceAtomicallyAndPublishsChanged() {
        when(depositAccountMapper.selectOne(any())).thenReturn(account(800L, 30000L, THRESHOLD, DepositStatus.NORMAL));
        when(depositAccountMapper.mutateBalance(800L, 50000L)).thenReturn(80000L);
        when(feeRecordMapper.selectList(any())).thenReturn(List.of());
        stubTxnInsertBackfillsId();

        long txnId = service.deposit(new DepositRequest(7L, VISIT, 50000L, PaymentMethod.CASH, null, null));

        // 原子记账断言：恰一次、入账方向为正且金额=请求额（mutateBalance 以精确入参打桩，偏移即回读落空）
        verify(depositAccountMapper).mutateBalance(800L, 50000L);
        // 状态判定：有效余额 80000−0 ≥ 阈值 10000 → NORMAL，与存量一致零改写（updateById 与条件更新均不得触达）
        verify(depositAccountMapper, never()).updateById(any(DepositAccount.class));
        verify(depositAccountMapper, never()).update(any(), any());
        // 流水落库断言：类型 DEPOSIT、金额恒正、ACTIVE、操作者取登录上下文
        ArgumentCaptor<DepositTxn> txnCaptor = ArgumentCaptor.forClass(DepositTxn.class);
        verify(depositTxnMapper).insert(txnCaptor.capture());
        DepositTxn row = txnCaptor.getValue();
        assertThat(row.getAccountId()).isEqualTo(800L);
        assertThat(row.getTxnType()).isEqualTo(DepositTxnType.DEPOSIT);
        assertThat(row.getAmount()).isEqualTo(50000L);
        assertThat(row.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(row.getStatus()).isEqualTo(DepositTxnStatus.ACTIVE);
        assertThat(row.getOperator()).isEqualTo("E1001");
        assertThat(row.getOccurredAt()).isNotNull();
        assertThat(txnId).isEqualTo(9001L);
        // 事件断言走冻结机制：事务内应用事件（AFTER_COMMIT 出 MQ 归 BillingEventPublisher）
        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(evt.capture());
        BillingDomainEvent published = (BillingDomainEvent) evt.getValue();
        assertThat(published.eventType()).isEqualTo(BillingMessagingConstants.EVENT_DEPOSIT_CHANGED);
        DepositChangedPayload payload = (DepositChangedPayload) published.payload();
        assertThat(payload.accountId()).isEqualTo(800L);
        assertThat(payload.patientId()).isEqualTo(7L);
        assertThat(payload.visitId()).isEqualTo(VISIT);
        assertThat(payload.balance()).isEqualTo(80000L); // 事件余额=原子回读值（非入参推算）
        assertThat(payload.status()).isEqualTo("NORMAL");
        // 账户定位 SQL 守卫钉死：等值条件必须落在 visit_id 列且携带本次就诊号（一就诊一账户，防跨就诊串户）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<DepositAccount>> accCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(depositAccountMapper).selectOne(accCaptor.capture());
        LambdaQueryWrapper<DepositAccount> accWrapper = (LambdaQueryWrapper<DepositAccount>) accCaptor.getValue();
        assertThat(accWrapper.getSqlSegment()).contains("visit_id");
        assertThat(accWrapper.getParamNameValuePairs().values()).contains(VISIT);
        // 已确认费用聚合 SQL 守卫钉死：visit_id+status 等值且携带就诊号与 CONFIRMED（欠费判定人群）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<FeeRecord>> feeCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(feeRecordMapper).selectList(feeCaptor.capture());
        LambdaQueryWrapper<FeeRecord> feeWrapper = (LambdaQueryWrapper<FeeRecord>) feeCaptor.getValue();
        assertThat(feeWrapper.getSqlSegment()).contains("visit_id").contains("status");
        assertThat(feeWrapper.getParamNameValuePairs().values()).contains(VISIT, FeeStatus.CONFIRMED);
    }

    @Test
    @DisplayName("欠费切换：回读余额−已确认费用 < 阈值 → 仅 SET status 转 ARREARS + 事件 status=ARREARS（SET 列守卫禁覆写余额）")
    void balanceBelowThresholdSwitchsAccountToArrearsAndPublishs() {
        // stale 与回读值刻意取不同值（selectOne 读 12000 / mutateBalance 回读 3000）：状态迁移若回写
        //   全实体，stale balance 12000 将覆写刚记账的 3000——SET 列守卫与事件回读断言当场失败兜底
        when(depositAccountMapper.selectOne(any())).thenReturn(account(800L, 12000L, THRESHOLD, DepositStatus.NORMAL));
        when(depositAccountMapper.mutateBalance(800L, 50000L)).thenReturn(3000L);
        when(feeRecordMapper.selectList(any())).thenReturn(List.of(confirmedFee(1L, 5000L)));
        stubTxnInsertBackfillsId();
        when(depositAccountMapper.update(isNull(), any())).thenReturn(1);

        service.deposit(new DepositRequest(7L, VISIT, 50000L, PaymentMethod.CASH, "CHN123", null));

        // 有效余额 3000−5000<阈值 10000 → ARREARS；状态迁移走仅 SET status 的条件更新（不走 updateById）
        ArgumentCaptor<Wrapper<DepositAccount>> updCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(depositAccountMapper).update(isNull(), updCaptor.capture());
        LambdaUpdateWrapper<DepositAccount> updWrapper = (LambdaUpdateWrapper<DepositAccount>) updCaptor.getValue();
        // 资金红线（D-13 同款守卫）：SET 子句只含 status，balance 永不回写——mutateBalance 是余额唯一写点
        assertThat(updWrapper.getSqlSet()).doesNotContain("balance");
        assertThat(updWrapper.getSqlSet()).contains("status");
        // 先物化 WHERE 段再断言参数：MP 条件参数为惰性求值（getSqlSegment() 首调时才写入
        // paramNameValuePairs），顺序颠倒会读到只有 SET 参数的中间态
        assertThat(updWrapper.getSqlSegment()).contains("id");
        assertThat(updWrapper.getParamNameValuePairs().values()).contains(DepositStatus.ARREARS, 800L);
        verify(depositAccountMapper, never()).updateById(any(DepositAccount.class));
        // 事件 status 出 ARREARS（M01 通知/M04 欠费提醒消费锚点），余额出回读值 3000（非 stale 12000）
        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(evt.capture());
        DepositChangedPayload payload = (DepositChangedPayload) ((BillingDomainEvent) evt.getValue()).payload();
        assertThat(payload.balance()).isEqualTo(3000L);
        assertThat(payload.status()).isEqualTo("ARREARS");
    }

    @Test
    @DisplayName("终态拒缴存：SETTLED/CLOSED 账户 BILL-1023 显式拒（原路语义，禁向已结清/销户账户入金）")
    void depositOnSettledAccountBlockedAsBill1023() {
        when(depositAccountMapper.selectOne(any()))
                .thenReturn(
                        account(800L, 0L, THRESHOLD, DepositStatus.SETTLED),
                        account(801L, 0L, THRESHOLD, DepositStatus.CLOSED));

        assertThatThrownBy(() -> service.deposit(new DepositRequest(7L, VISIT, 50000L, PaymentMethod.CASH, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.DEPOSIT_TXN_STATE_NOT_ALLOWED));
        assertThatThrownBy(() -> service.deposit(new DepositRequest(7L, VISIT, 50000L, PaymentMethod.CASH, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.DEPOSIT_TXN_STATE_NOT_ALLOWED));
        // 拒缴即零资金动作：不记账、不落流水、不发事件
        verify(depositAccountMapper, never()).mutateBalance(anyLong(), anyLong());
        verifyNoInteractions(depositTxnMapper, events);
    }

    @Test
    @DisplayName("开户缴存：首缴零余额建户再原子记账；阈值缺省取 BillingProperties，显式传入即覆盖并可开户即欠费")
    void depositOpensAccountWithThresholdFallbackOnFirstPayment() {
        when(depositAccountMapper.selectOne(any())).thenReturn(null, null);
        // 模拟 MP ASSIGN_ID 回填：insert 时给实体置 id
        when(depositAccountMapper.insert(any(DepositAccount.class))).thenAnswer(inv -> {
            inv.getArgument(0, DepositAccount.class).setId(801L);
            return 1;
        });
        when(depositAccountMapper.mutateBalance(801L, 50000L)).thenReturn(50000L);
        when(feeRecordMapper.selectList(any())).thenReturn(List.of());
        stubTxnInsertBackfillsId();
        when(depositAccountMapper.update(isNull(), any())).thenReturn(1);

        // 场景一：未显式传阈值 → 开户取 BillingProperties 默认 10000；首缴 50000 ≥ 10000 维持 NORMAL
        service.deposit(new DepositRequest(7L, VISIT, 50000L, PaymentMethod.SCAN, "CHN1", null));
        // 场景二：显式传阈值 60000 → 开户即覆盖；首缴 50000 < 60000 → 开户即 ARREARS 状态落库
        service.deposit(new DepositRequest(7L, VISIT, 50000L, PaymentMethod.SCAN, "CHN2", 60000L));

        // 开户落库断言：余额唯一写点红线——insert 恒零余额，阈值按缺省/显式两分支各自生效
        ArgumentCaptor<DepositAccount> insCaptor = ArgumentCaptor.forClass(DepositAccount.class);
        verify(depositAccountMapper, times(2)).insert(insCaptor.capture());
        assertThat(insCaptor.getAllValues().get(0).getBalance()).isZero();
        assertThat(insCaptor.getAllValues().get(0).getWarningThreshold()).isEqualTo(THRESHOLD);
        assertThat(insCaptor.getAllValues().get(0).getStatus()).isEqualTo(DepositStatus.NORMAL);
        assertThat(insCaptor.getAllValues().get(1).getWarningThreshold()).isEqualTo(60000L);
        // 场景二开户即欠费：50000−0 < 60000 → ARREARS 仅 SET status 落库（场景一 NORMAL 零改写）
        ArgumentCaptor<Wrapper<DepositAccount>> updCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(depositAccountMapper, times(1)).update(isNull(), updCaptor.capture());
        LambdaUpdateWrapper<DepositAccount> updWrapper = (LambdaUpdateWrapper<DepositAccount>) updCaptor.getValue();
        // SET 守卫：开户实体携带 balance=0 脏值，全实体回写会把 mutateBalance 刚记的首缴余额清零
        assertThat(updWrapper.getSqlSet()).doesNotContain("balance");
        assertThat(updWrapper.getSqlSet()).contains("status");
        verify(depositAccountMapper, times(2)).mutateBalance(801L, 50000L);
        // 两次缴存各发一笔 deposit.changed，第二笔 status=ARREARS
        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishEvent(evt.capture());
        List<Object> published = evt.getAllValues();
        assertThat(((DepositChangedPayload) ((BillingDomainEvent) published.get(0)).payload()).status())
                .isEqualTo("NORMAL");
        assertThat(((DepositChangedPayload) ((BillingDomainEvent) published.get(1)).payload()).status())
                .isEqualTo("ARREARS");
    }

    @Test
    @DisplayName("支付方式守卫：CARD_BALANCE/CHARGE_ON_CREDIT 混入缴存 400 显式拒（V604 列口径仅 CASH/BANK/SCAN/ONLINE）")
    void depositRejectsSettlementOnlyPaymentMethodsAs400() {
        assertThatThrownBy(() ->
                        service.deposit(new DepositRequest(7L, VISIT, 50000L, PaymentMethod.CARD_BALANCE, "5", null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING));
        assertThatThrownBy(() -> service.deposit(
                        new DepositRequest(7L, VISIT, 50000L, PaymentMethod.CHARGE_ON_CREDIT, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING));
        // 守卫在资金动作之前：账户/流水/费用/事件零触碰
        verifyNoInteractions(depositAccountMapper, depositTxnMapper, feeRecordMapper, events);
    }

    @Test
    @DisplayName("并发终态竞争：mutateBalance 回读落空 BILL-1023 拒且不落孤儿流水（记账回读为欠费判定与入账唯一凭据）")
    void depositRejectsWhenAtomicReadbackMissesAsBill1023() {
        when(depositAccountMapper.selectOne(any())).thenReturn(account(800L, 30000L, THRESHOLD, DepositStatus.NORMAL));
        when(depositAccountMapper.mutateBalance(800L, 50000L)).thenReturn(null);

        assertThatThrownBy(() -> service.deposit(new DepositRequest(7L, VISIT, 50000L, PaymentMethod.CASH, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.DEPOSIT_TXN_STATE_NOT_ALLOWED));
        verify(depositTxnMapper, never()).insert(any(DepositTxn.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("就诊号守卫：非 14 位定长与门诊 O 前缀均 BILL-1013 拒（住院预交金唯一受理形态）")
    void depositRejectsMalformedOrOutpatientVisitIdAs400() {
        assertThatThrownBy(
                        () -> service.deposit(new DepositRequest(7L, "I2026", 50000L, PaymentMethod.CASH, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.VISIT_ID_MALFORMED));
        assertThatThrownBy(() -> service.deposit(
                        new DepositRequest(7L, "O2026091600001", 50000L, PaymentMethod.CASH, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.VISIT_ID_MALFORMED));
        verifyNoInteractions(depositAccountMapper, depositTxnMapper, feeRecordMapper, events);
    }

    @Test
    @DisplayName("操作者守卫：缺登录上下文 BILL-1012 拒（资金动作不可追溯即拒，退费申请同款红线）")
    void depositRejectsWithoutOperatorContextAs400() {
        OperatorContextHolder.clear();

        assertThatThrownBy(() -> service.deposit(new DepositRequest(7L, VISIT, 50000L, PaymentMethod.CASH, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING));
        verifyNoInteractions(depositAccountMapper, depositTxnMapper, feeRecordMapper, events);
    }

    @Test
    @DisplayName("账户查询：命中出 VO（余额/阈值/状态 code 原样出网）且定位 SQL 钉死 visit_id")
    void getByVisitReturnsAccountVoWhenFound() {
        when(depositAccountMapper.selectOne(any())).thenReturn(account(800L, 30000L, THRESHOLD, DepositStatus.NORMAL));

        DepositAccountVO vo = service.getByVisit(VISIT);

        assertThat(vo.id()).isEqualTo(800L);
        assertThat(vo.patientId()).isEqualTo(7L);
        assertThat(vo.visitId()).isEqualTo(VISIT);
        assertThat(vo.balance()).isEqualTo(30000L);
        assertThat(vo.warningThreshold()).isEqualTo(THRESHOLD);
        assertThat(vo.status()).isEqualTo("NORMAL");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<DepositAccount>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(depositAccountMapper).selectOne(captor.capture());
        LambdaQueryWrapper<DepositAccount> wrapper = (LambdaQueryWrapper<DepositAccount>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("visit_id");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(VISIT);
    }

    @Test
    @DisplayName("账户查询：无账户 BILL-1022 显式拒（404）")
    void getByVisitRejectsMissingAccountAsBill1022() {
        when(depositAccountMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.getByVisit(VISIT))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.DEPOSIT_ACCOUNT_NOT_FOUND));
    }
}
