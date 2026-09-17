package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.CardTxnRecord;
import com.fuyun.patient.api.CardTxnType;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.entity.CardAccount;
import com.fuyun.patient.entity.CardTxn;
import com.fuyun.patient.mapper.CardAccountMapper;
import com.fuyun.patient.mapper.CardTxnMapper;
import com.fuyun.patient.properties.PatientCardProperties;
import com.fuyun.patient.service.ICardAccountService;
import com.fuyun.patient.vo.CardAccountVO;
import com.fuyun.patient.vo.CardTxnVO;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 一卡通账户实现单测（资金红线）：记账三分支（原子回读/金额恒正/不超扣）+ 状态守卫
 * （销户余额未清/CLOSED 拒动）+ 开户开关（默认关闭不建户）+ 流水分页与按患者查询。
 */
@ExtendWith(MockitoExtension.class)
class CardAccountServiceImplTest {

    @Mock
    private CardAccountMapper cardAccountMapper;

    @Mock
    private CardTxnMapper cardTxnMapper;

    private ICardAccountService cardAccountService;

    /** 账户链式查询替身返回集（lambdaQuery().one() 经 baseMapper.selectOne 承载） */
    private List<CardAccount> accountQueryResult = List.of();

    @BeforeAll
    static void initTableInfo() {
        // 单测容器外手动初始化实体表信息（lambda 列名解析依赖 TableInfo，模块内既有 ServiceImpl 单测同款）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CardAccount.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CardTxn.class);
    }

    @BeforeEach
    void setUp() {
        cardAccountService = serviceWith(true);
    }

    /** 构造被测服务并注入 mock baseMapper（容器外单测，ReflectionTestUtils 注入，模块内同款） */
    private ICardAccountService serviceWith(boolean accountEnabled) {
        CardAccountServiceImpl impl =
                new CardAccountServiceImpl(cardTxnMapper, new PatientCardProperties(accountEnabled));
        ReflectionTestUtils.setField(impl, "baseMapper", cardAccountMapper);
        ReflectionTestUtils.setField(impl, "entityClass", CardAccount.class);
        return impl;
    }

    private CardAccount accountRow(Long id, Long balance, String status) {
        CardAccount row = new CardAccount();
        row.setId(id);
        row.setPatientId(5L);
        row.setBalance(balance);
        row.setStatus(status);
        return row;
    }

    @Test
    @DisplayName("充值记账成功：RETURNING 回读值落 balance_after 对账锚点并返回流水 id")
    void recordRechargePersistsTxnWithAtomicBalance() {
        // recordBalance 承载 UPDATE ... RETURNING 语义：回读记账后余额 5000 分
        when(cardAccountMapper.recordBalance(7L, 5000L)).thenReturn(5000L);
        // 模拟 ASSIGN_ID 插入期回填主键（与生产参数处理器行为一致）
        when(cardTxnMapper.insert(any(CardTxn.class))).thenAnswer(inv -> {
            inv.getArgument(0, CardTxn.class).setId(88L);
            return 1;
        });

        long txnId = cardAccountService.record(new CardTxnRecord(7L, CardTxnType.RECHARGE, 5000L, "BILL-1"));

        assertThat(txnId).isEqualTo(88L);
        ArgumentCaptor<CardTxn> captor = ArgumentCaptor.forClass(CardTxn.class);
        verify(cardTxnMapper).insert(captor.capture());
        CardTxn saved = captor.getValue();
        assertThat(saved.getAccountId()).isEqualTo(7L);
        assertThat(saved.getTxnType()).isEqualTo("RECHARGE");
        assertThat(saved.getAmount()).isEqualTo(5000L);
        // 资金锚点：balance_after 必须取 RETURNING 原子回读值，禁二次计算
        assertThat(saved.getBalanceAfter()).isEqualTo(5000L);
        assertThat(saved.getBizRef()).isEqualTo("BILL-1");
    }

    @Test
    @DisplayName("PAY 出账正常路径：增量以负数入账且 balance_after 取回读值（出账即减）")
    void recordPayDebitPassesNegativeDeltaAndPersistsBalanceAfter() {
        when(cardAccountMapper.recordBalance(7L, -300L)).thenReturn(4700L);
        when(cardTxnMapper.insert(any(CardTxn.class))).thenAnswer(inv -> {
            inv.getArgument(0, CardTxn.class).setId(89L);
            return 1;
        });

        long txnId = cardAccountService.record(new CardTxnRecord(7L, CardTxnType.PAY, 300L, "BILL-2"));

        assertThat(txnId).isEqualTo(89L);
        // 出账方向折算：delta 必须为负（方向由 txn_type 表达，金额恒正）
        ArgumentCaptor<Long> deltaCaptor = ArgumentCaptor.forClass(Long.class);
        verify(cardAccountMapper).recordBalance(eq(7L), deltaCaptor.capture());
        assertThat(deltaCaptor.getValue()).isEqualTo(-300L);
        ArgumentCaptor<CardTxn> captor = ArgumentCaptor.forClass(CardTxn.class);
        verify(cardTxnMapper).insert(captor.capture());
        assertThat(captor.getValue().getBalanceAfter()).isEqualTo(4700L);
    }

    @Test
    @DisplayName("金额恒正契约收口：零值与负值一律 PAT-1022（400），不触达余额与流水")
    void recordRejectsNonPositiveAmountAsPat1022() {
        assertThatThrownBy(() -> cardAccountService.record(new CardTxnRecord(7L, CardTxnType.RECHARGE, 0L, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_TXN_AMOUNT_INVALID));
        assertThatThrownBy(() -> cardAccountService.record(new CardTxnRecord(7L, CardTxnType.PAY, -100L, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_TXN_AMOUNT_INVALID));
        verifyNoInteractions(cardAccountMapper, cardTxnMapper);
    }

    @Test
    @DisplayName("PAY 余额不足：PAT-1016 拒绝不超扣，流水不落库（RETURNING 为负即回滚口径）")
    void recordPayWithInsufficientBalanceRejectedAsPat1016() {
        when(cardAccountMapper.recordBalance(7L, -300L)).thenReturn(-200L);

        assertThatThrownBy(() -> cardAccountService.record(new CardTxnRecord(7L, CardTxnType.PAY, 300L, "BILL-3")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_INSUFFICIENT_BALANCE));
        verify(cardTxnMapper, never()).insert(any(CardTxn.class));
    }

    @Test
    @DisplayName("记账遇不存在账户：RETURNING 无行且查无此户 → PAT-1013（404）")
    void recordOnMissingAccountFailsAsPat1013() {
        when(cardAccountMapper.recordBalance(7L, 5000L)).thenReturn(null);
        when(cardAccountMapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> cardAccountService.record(new CardTxnRecord(7L, CardTxnType.RECHARGE, 5000L, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_NOT_FOUND));
    }

    @Test
    @DisplayName("记账遇非 ACTIVE 账户：RETURNING 无行且行存续（FROZEN）→ PAT-1014（409）")
    void recordOnNonActiveAccountFailsAsPat1014() {
        when(cardAccountMapper.recordBalance(7L, 5000L)).thenReturn(null);
        when(cardAccountMapper.selectById(7L)).thenReturn(accountRow(7L, 0L, "FROZEN"));

        assertThatThrownBy(() -> cardAccountService.record(new CardTxnRecord(7L, CardTxnType.RECHARGE, 5000L, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("启用开关关闭：开户返回 null 不建户（默认安全姿态，发卡联动入口）")
    void openIfEnabledReturnsNullWhenSwitchOff() {
        ICardAccountService disabled = serviceWith(false);

        assertThat(disabled.openIfEnabled(5L)).isNull();
        verifyNoInteractions(cardAccountMapper);
    }

    @Test
    @DisplayName("启用且无账户：开户建户余额 0 分、状态 ACTIVE 并返回账户 id")
    void openCreatesAccountWithZeroBalanceWhenEnabled() {
        when(cardAccountMapper.selectOne(any())).thenReturn(null);
        when(cardAccountMapper.insert(any(CardAccount.class))).thenAnswer(inv -> {
            inv.getArgument(0, CardAccount.class).setId(99L);
            return 1;
        });

        Long accountId = cardAccountService.openIfEnabled(5L);

        assertThat(accountId).isEqualTo(99L);
        ArgumentCaptor<CardAccount> captor = ArgumentCaptor.forClass(CardAccount.class);
        verify(cardAccountMapper).insert(captor.capture());
        assertThat(captor.getValue().getPatientId()).isEqualTo(5L);
        assertThat(captor.getValue().getBalance()).isZero();
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("启用且已有账户：幂等返回既有账户 id 不重复建户（一人一账户）")
    void openReturnsExistingAccountWithoutRecreating() {
        accountQueryResult = List.of(accountRow(66L, 100L, "ACTIVE"));
        when(cardAccountMapper.selectOne(any())).thenReturn(accountQueryResult.get(0));

        Long accountId = cardAccountService.openIfEnabled(5L);

        assertThat(accountId).isEqualTo(66L);
        verify(cardAccountMapper, never()).insert(any(CardAccount.class));
    }

    @Test
    @DisplayName("销户余额未清：条件更新 0 行受影响，复读定性为 PAT-1015（409）")
    void closeRejectsUnsettledBalanceAsPat1015() {
        when(cardAccountMapper.selectById(7L)).thenReturn(accountRow(7L, 500L, "ACTIVE"));

        assertThatThrownBy(() -> cardAccountService.close(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_BALANCE_NOT_SETTLED));
        // 条件更新（balance=0 谓词）0 行命中：Mockito 对未打桩 update 默认返回 0，无需显式打桩
        verify(cardAccountMapper, never()).updateById(any(CardAccount.class));
    }

    @Test
    @DisplayName("销户成功：条件更新置 CLOSED+销户时刻，SET 子句不含余额列（D-13 收口）")
    void closeSetsClosedStatusWhenBalanceZero() {
        when(cardAccountMapper.selectById(7L)).thenReturn(accountRow(7L, 0L, "ACTIVE"));
        when(cardAccountMapper.update(isNull(), any())).thenReturn(1);

        cardAccountService.close(7L);

        ArgumentCaptor<Wrapper<CardAccount>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(cardAccountMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<CardAccount> wrapper = (LambdaUpdateWrapper<CardAccount>) captor.getValue();
        // D-13 红线：SET 只含状态与销户时刻，balance 永不回写（并发记账不被 stale 读覆写）
        assertThat(wrapper.getSqlSet()).doesNotContain("balance");
        assertThat(wrapper.getSqlSet()).contains("status").contains("closed_at");
        assertThat(wrapper.getParamNameValuePairs().containsValue("CLOSED")).isTrue();
    }

    @Test
    @DisplayName("冻结 ACTIVE：切至 FROZEN 且 SET 不含余额列")
    void freezeTogglesActiveToFrozen() {
        when(cardAccountMapper.selectById(7L)).thenReturn(accountRow(7L, 100L, "ACTIVE"));
        when(cardAccountMapper.update(isNull(), any())).thenReturn(1);

        cardAccountService.freeze(7L);

        ArgumentCaptor<Wrapper<CardAccount>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(cardAccountMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<CardAccount> wrapper = (LambdaUpdateWrapper<CardAccount>) captor.getValue();
        assertThat(wrapper.getSqlSet()).doesNotContain("balance").contains("status");
        assertThat(wrapper.getParamNameValuePairs().containsValue("FROZEN")).isTrue();
    }

    @Test
    @DisplayName("挂失联动冻结：ACTIVE 命中条件更新，SET 不含余额列")
    void freezeByPatientSetsFrozenOnActiveAccount() {
        when(cardAccountMapper.selectOne(any())).thenReturn(accountRow(7L, 300L, "ACTIVE"));
        when(cardAccountMapper.update(isNull(), any())).thenReturn(1);

        cardAccountService.freezeByPatient(42L);

        ArgumentCaptor<Wrapper<CardAccount>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(cardAccountMapper).update(isNull(), captor.capture());
        assertThat(((LambdaUpdateWrapper<CardAccount>) captor.getValue()).getSqlSet())
                .doesNotContain("balance");
    }

    @Test
    @DisplayName("挂失联动冻结写窗口状态竞态：条件更新 0 行命中静默放弃（挂失语义吞咽，无全量回写）")
    void freezeByPatientRaceSkipsSilentlyWhenZeroRows() {
        when(cardAccountMapper.selectOne(any())).thenReturn(accountRow(66L, 100L, "ACTIVE"));

        cardAccountService.freezeByPatient(5L);

        verify(cardAccountMapper, never()).updateById(any(CardAccount.class));
    }

    @Test
    @DisplayName("按患者挂失联动遇已销户账户：CLOSED 终态静默跳过不触发写操作")
    void freezeByPatientSkipsClosedAccountSilently() {
        when(cardAccountMapper.selectOne(any())).thenReturn(accountRow(66L, 0L, "CLOSED"));

        cardAccountService.freezeByPatient(5L);

        verify(cardAccountMapper, never()).updateById(any(CardAccount.class));
    }

    @Test
    @DisplayName("按患者挂失联动遇无账户：PAT-1013（404，由调用方决定吞咽口径）")
    void freezeByPatientWithoutAccountFailsAsPat1013() {
        when(cardAccountMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> cardAccountService.freezeByPatient(5L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_NOT_FOUND));
    }

    @Test
    @DisplayName("解冻 FROZEN：切回 ACTIVE（成对切换同一条件更新通道）")
    void freezeTogglesFrozenBackToActive() {
        when(cardAccountMapper.selectById(7L)).thenReturn(accountRow(7L, 100L, "FROZEN"));
        when(cardAccountMapper.update(isNull(), any())).thenReturn(1);

        cardAccountService.freeze(7L);

        ArgumentCaptor<Wrapper<CardAccount>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(cardAccountMapper).update(isNull(), captor.capture());
        assertThat(((LambdaUpdateWrapper<CardAccount>) captor.getValue())
                        .getParamNameValuePairs()
                        .containsValue("ACTIVE"))
                .isTrue();
    }

    @Test
    @DisplayName("CLOSED 账户冻结/销户：前置守卫 409 PAT-1014，不发条件更新")
    void freezeOnClosedAccountFailsAsPat1014() {
        when(cardAccountMapper.selectById(7L)).thenReturn(accountRow(7L, 0L, "CLOSED"));

        assertThatThrownBy(() -> cardAccountService.freeze(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_STATE_NOT_ALLOWED));
        assertThatThrownBy(() -> cardAccountService.close(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_STATE_NOT_ALLOWED));
        verify(cardAccountMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("冻结/销户遇不存在账户：PAT-1013（404）")
    void guardOnMissingAccountFailsAsPat1013() {
        when(cardAccountMapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> cardAccountService.freeze(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_NOT_FOUND));
        assertThatThrownBy(() -> cardAccountService.close(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_NOT_FOUND));
    }

    @Test
    @DisplayName("已销户账户禁再销户：PAT-1014（状态机终态守卫）")
    void closeOnClosedAccountFailsAsPat1014() {
        when(cardAccountMapper.selectById(7L)).thenReturn(accountRow(7L, 0L, "CLOSED"));

        assertThatThrownBy(() -> cardAccountService.close(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("D-13 并发窗口回归：读取后余额被并发记账改写，条件更新仍不落败旧值（SET 无 balance 列）")
    void concurrentRecordBetweenReadAndStatusUpdateNeverRewritesBalance() {
        // 模拟竞态窗口（第 2 轮审查 P2-1 订正打桩形态）：同一 selectById 变参链依次命中——
        //   首读 balance=0 的 ACTIVE 行，并发记账提交后复读行余额已为 800（本行不再可见）
        when(cardAccountMapper.selectById(7L)).thenReturn(accountRow(7L, 0L, "ACTIVE"), accountRow(7L, 800L, "ACTIVE"));
        // 条件更新携带 balance=0 谓词 → 并发记账提交后 0 行命中，销户必须失败而非覆写余额
        when(cardAccountMapper.update(isNull(), any())).thenReturn(0);

        // 复读定性：实现按「0 行受影响 → 重读账户分类失败原因」处理；重读 balance≠0 → PAT-1015
        assertThatThrownBy(() -> cardAccountService.close(7L)).isInstanceOf(BizException.class);
        verify(cardAccountMapper, never()).updateById(any(CardAccount.class));
    }

    @Test
    @DisplayName("D-13 销户写窗口内账户被并发删除：条件更新 0 行命中复读无行 → PAT-1013（404）")
    void closeWithAccountDeletedInWindowFailsAsPat1013() {
        // 首读 ACTIVE 余额零（守卫通过），写窗口内整行消失（并发删除），复读无行
        when(cardAccountMapper.selectById(7L)).thenReturn(accountRow(7L, 0L, "ACTIVE"), (CardAccount) null);

        assertThatThrownBy(() -> cardAccountService.close(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_NOT_FOUND));
        verify(cardAccountMapper, never()).updateById(any(CardAccount.class));
    }

    @Test
    @DisplayName("D-13 销户状态竞态：0 行命中且复读余额仍为零（非余额原因拦截）→ PAT-1014（409）")
    void closeRaceWithBalanceStillZeroFailsAsPat1014() {
        // 首读与复读均为 ACTIVE 余额零：0 行命中只能源于窗口内状态变化（如并发冻结到非预期态）
        when(cardAccountMapper.selectById(7L)).thenReturn(accountRow(7L, 0L, "ACTIVE"));

        assertThatThrownBy(() -> cardAccountService.close(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_STATE_NOT_ALLOWED));
        verify(cardAccountMapper, never()).updateById(any(CardAccount.class));
    }

    @Test
    @DisplayName("D-13 冻结写窗口状态竞态：条件更新 0 行命中复读仍非终态 → PAT-1014 竞态拒切")
    void freezeRaceZeroRowsReclassifiedAsPat1014() {
        // 首读 ACTIVE 守卫通过，写窗口内并发操作令条件谓词落空，复读仍非 CLOSED（竞态兜底拒绝）
        when(cardAccountMapper.selectById(7L)).thenReturn(accountRow(7L, 100L, "ACTIVE"));

        assertThatThrownBy(() -> cardAccountService.freeze(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_STATE_NOT_ALLOWED));
        verify(cardAccountMapper, never()).updateById(any(CardAccount.class));
    }

    @Test
    @DisplayName("账户流水分页：按账户过滤时序倒序，balance_after 对账锚点直出（wrapper 断言锁定列）")
    void listTxnsReturnsPageFilteredByAccountInDescendingOrder() {
        CardTxn txnRow = new CardTxn();
        txnRow.setId(88L);
        txnRow.setAccountId(7L);
        txnRow.setTxnType("RECHARGE");
        txnRow.setAmount(5000L);
        txnRow.setBalanceAfter(5000L);
        txnRow.setBizRef("BILL-1");
        when(cardTxnMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<CardTxn> page = inv.getArgument(0);
            page.setRecords(List.of(txnRow));
            page.setTotal(1);
            return page;
        });

        PageResult<CardTxnVO> result = cardAccountService.listTxns(7L, 0, 20);

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).getId()).isEqualTo(88L);
        assertThat(result.content().get(0).getBalanceAfter()).isEqualTo(5000L);
        // wrapper 断言（3.5.17：条件参数在 getSqlSegment 惰性求值时才写入 paramNameValuePairs）
        ArgumentCaptor<Wrapper<CardTxn>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(cardTxnMapper).selectPage(any(), wrapperCaptor.capture());
        LambdaQueryWrapper<CardTxn> wrapper = (LambdaQueryWrapper<CardTxn>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("account_id").containsIgnoringCase("occurred_at");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(7L);
    }

    @Test
    @DisplayName("按患者取账户：命中返回余额/状态直映出参")
    void getByPatientReturnsAccountView() {
        when(cardAccountMapper.selectOne(any())).thenReturn(accountRow(66L, 100L, "ACTIVE"));

        CardAccountVO view = cardAccountService.getByPatient(5L);

        assertThat(view.getId()).isEqualTo(66L);
        assertThat(view.getPatientId()).isEqualTo(5L);
        assertThat(view.getBalance()).isEqualTo(100L);
        assertThat(view.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("按患者取账户无命中：PAT-1013（404）")
    void getByPatientWithoutAccountFailsAsPat1013() {
        when(cardAccountMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> cardAccountService.getByPatient(5L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.CARD_ACCOUNT_NOT_FOUND));
    }
}
