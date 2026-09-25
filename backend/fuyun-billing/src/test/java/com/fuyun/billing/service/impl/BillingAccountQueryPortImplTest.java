package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.api.DischargePrecheckView;
import com.fuyun.billing.entity.DepositAccount;
import com.fuyun.billing.mapper.DepositAccountMapper;
import com.fuyun.billing.mapper.FeeRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 出院费用预审端口单测（P2 PR-1 Task 13，brief 冻结用例⑩承载面；Task 9 inpatient 侧 mock 语义
 * 的实现面回验）：precheck 聚合三值——未结清合计（PENDING+CONFIRMED 未结算）/押金余额（无账户 0）/
 * 是否结清布尔（未结清 ≤ 押金余额）。GC24 单测构造范式同款。
 */
@ExtendWith(MockitoExtension.class)
class BillingAccountQueryPortImplTest {

    private static final String VISIT = "I20260925000001";

    @Mock
    private FeeRecordMapper feeRecordMapper;

    @Mock
    private DepositAccountMapper depositAccountMapper;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DepositAccount.class);
    }

    @Test
    @DisplayName("⑩ 预审结清：未结清合计 ≤ 押金余额 → settled=true（押金覆盖内预审通过）")
    void precheckSettledWhenDepositCoversUnsettled() {
        when(feeRecordMapper.sumUnsettledAmount(VISIT)).thenReturn(50000L);
        when(depositAccountMapper.selectOne(any(Wrapper.class))).thenReturn(account(80000L));

        DischargePrecheckView view =
                new BillingAccountQueryPortImpl(feeRecordMapper, depositAccountMapper).precheck(VISIT);

        assertThat(view.unsettledAmount()).isEqualTo(50000L);
        assertThat(view.depositBalance()).isEqualTo(80000L);
        assertThat(view.settled()).isTrue();
    }

    @Test
    @DisplayName("⑩ 预审欠费：未结清合计 > 押金余额 → settled=false（BLOCKED 挂账审批前置）")
    void precheckBlockedWhenArrearsExceedDeposit() {
        when(feeRecordMapper.sumUnsettledAmount(VISIT)).thenReturn(50000L);
        when(depositAccountMapper.selectOne(any(Wrapper.class))).thenReturn(account(30000L));

        DischargePrecheckView view =
                new BillingAccountQueryPortImpl(feeRecordMapper, depositAccountMapper).precheck(VISIT);

        assertThat(view.settled()).isFalse();
    }

    @Test
    @DisplayName("⑩ 补面：无押金账户余额按 0 口径（接口冻结——无费用行与零余额押金账户返回零值视图视为结清）")
    void precheckWithoutAccountTreatsBalanceAsZero() {
        when(feeRecordMapper.sumUnsettledAmount(VISIT)).thenReturn(0L);
        when(depositAccountMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        DischargePrecheckView view =
                new BillingAccountQueryPortImpl(feeRecordMapper, depositAccountMapper).precheck(VISIT);

        assertThat(view.unsettledAmount()).isZero();
        assertThat(view.depositBalance()).isZero();
        assertThat(view.settled()).isTrue();
    }

    /** 押金账户夹具（余额=入参分值） */
    private DepositAccount account(long balance) {
        DepositAccount account = new DepositAccount();
        account.setVisitId(VISIT);
        account.setBalance(balance);
        return account;
    }
}
