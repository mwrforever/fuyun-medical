package com.fuyun.billing.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.dto.InsurancePreSettleCommand;
import com.fuyun.billing.dto.InsurancePreSettleResult;
import com.fuyun.common.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 医保模拟适应器单测（FU-M13-05，已裁决 3 口径）：确定性基金拆分勾稽恒等（红线 1：五拆分和=总额，
 * 尾差归自付）、目录外行全额自费、先自付按比例 HALF_UP、撤销/登记/费用上传确定值与电子凭证
 * 空/空白 BILL-1024 显式拒（禁空凭证静默放行）。零 IO 纯计算，无需 mock。
 */
class InsuranceSimulatorAdapterTest {

    private final InsuranceSimulatorAdapter adapter = new InsuranceSimulatorAdapter();

    /** 预结算命令夹具（门诊就诊号 + 幂等键=结算编号语义） */
    private InsurancePreSettleCommand cmd(InsurancePreSettleCommand.Line... lines) {
        return new InsurancePreSettleCommand(0L, "O2026091700001", List.of(lines), "S100");
    }

    @Test
    @DisplayName("目录内甲类 10000 分拆分：统筹 6000/个账 2000/自付 2000，勾稽恒等五和=总额且流水号确定性派生")
    void preSettleSplitsInCatalogAmountIntoPooledAcctSelf() {
        // 甲类行：先自付比例缺省（null=0，全额进目录拆分）
        InsurancePreSettleResult result =
                adapter.preSettle(cmd(new InsurancePreSettleCommand.Line("NHBZ-TREAT-001", null, null, 10000L)));

        assertThat(result.totalAmount()).isEqualTo(10000L); // 与 cmd 费用行求和一致回显
        assertThat(result.pooledAmount()).isEqualTo(6000L); // 目录内余额 60% 统筹（HALF_UP 整分）
        assertThat(result.acctPayAmount()).isEqualTo(2000L); // 20% 个账
        assertThat(result.selfPayAmount()).isEqualTo(2000L); // 余额减两拆=自付
        assertThat(result.selfExpenseAmount()).isZero(); // 无目录外行
        assertThat(result.preSelfPayAmount()).isZero(); // 比例缺省 0
        // 勾稽恒等式（Task 12 preview 二次勾稽 BILL-1016 消费的同源恒等，按构造必过）
        assertThat(result.pooledAmount()
                        + result.acctPayAmount()
                        + result.selfPayAmount()
                        + result.selfExpenseAmount()
                        + result.preSelfPayAmount())
                .isEqualTo(result.totalAmount());
        // 冻结签名口径：流水号由幂等键确定性派生（重放同值），目录版本=模拟目录戳
        assertThat(result.centerSerialNo()).isEqualTo("SIM-S100");
        assertThat(result.catalogVersion()).isEqualTo("SIM-2026Q3");
    }

    @Test
    @DisplayName("目录外行（nhsaCode=null 无对照）：全额进自费不参与基金拆分，勾稽恒等仍成立")
    void preSettleReturnsSelfExpenseForUnmappedFee() {
        InsurancePreSettleResult result =
                adapter.preSettle(cmd(new InsurancePreSettleCommand.Line(null, null, null, 10000L)));

        assertThat(result.selfExpenseAmount()).isEqualTo(10000L); // 目录外全额自费
        assertThat(result.pooledAmount()).isZero();
        assertThat(result.acctPayAmount()).isZero();
        assertThat(result.selfPayAmount()).isZero();
        assertThat(result.preSelfPayAmount()).isZero();
        assertThat(result.totalAmount()).isEqualTo(10000L);
        assertThat(result.pooledAmount()
                        + result.acctPayAmount()
                        + result.selfPayAmount()
                        + result.selfExpenseAmount()
                        + result.preSelfPayAmount())
                .isEqualTo(result.totalAmount());
    }

    @Test
    @DisplayName("乙类先自付路径：先按比例 HALF_UP 提先自付，余额再三分且尾差归自付保恒等")
    void preSettleDeductsPreSelfByRatioAndTrailsRemainderToSelfPay() {
        // 10005 分 × 10% 先自付 = 1000.5 → HALF_UP 1001（整分红线）；余额 9004 三分带尾差：
        // 统筹 5402.4→5402、个账 1800.8→1801、自付取余额减两拆=1801（尾差归自付）
        InsurancePreSettleResult result = adapter.preSettle(
                cmd(new InsurancePreSettleCommand.Line("NHBZ-DRUG-002", null, new BigDecimal("0.1"), 10005L)));

        assertThat(result.preSelfPayAmount()).isEqualTo(1001L);
        assertThat(result.pooledAmount()).isEqualTo(5402L);
        assertThat(result.acctPayAmount()).isEqualTo(1801L);
        assertThat(result.selfPayAmount()).isEqualTo(1801L);
        assertThat(result.totalAmount()).isEqualTo(10005L);
        assertThat(result.pooledAmount()
                        + result.acctPayAmount()
                        + result.selfPayAmount()
                        + result.selfExpenseAmount()
                        + result.preSelfPayAmount())
                .isEqualTo(result.totalAmount());
    }

    @Test
    @DisplayName("撤销接口位（2104）：按幂等键确定性派生撤销流水号（模拟即时成功语义）")
    void reverseReturnsCenterSerialAndMarksSuccess() {
        assertThat(adapter.reverse("SIM-S100", "REV-1")).isEqualTo("SIM-REV-REV-1");
    }

    @Test
    @DisplayName("电子凭证核验接口位：非空 ecToken 逐字派生 SIM-AUTH 流水号；空/空白 BILL-1024 拒且流水号不出网")
    void authenticateReturnsSimSerialForValidToken() {
        // 非空令牌：确定性凭证流水号（2026-09-17 裁决补位，逐字断言）
        assertThat(adapter.authenticate("EC-TOKEN-42")).isEqualTo("SIM-AUTH-EC-TOKEN-42");
        // 空与空白：BILL-1024 502 显式拒，禁空凭证静默放行且异常文案不带任何流水号
        assertThatThrownBy(() -> adapter.authenticate(null)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.INSURANCE_CALL_FAILED);
            assertThat(e.getMessage()).doesNotContain("SIM-AUTH");
        });
        assertThatThrownBy(() -> adapter.authenticate("  "))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.INSURANCE_CALL_FAILED));
    }

    @Test
    @DisplayName("门诊登记接口位（2001）：visitId 确定性派生中心流水号")
    void registerReturnsDeterministicCenterSerial() {
        assertThat(adapter.register("O2026091700001", 7L)).isEqualTo("SIM-REG-O2026091700001");
    }

    @Test
    @DisplayName("费用上传接口位（2101）：回显上传行数")
    void feeUploadReturnsSubmittedLineCount() {
        assertThat(adapter.feeUpload("O2026091700001", List.of(1L, 2L, 3L))).isEqualTo(3);
    }
}
