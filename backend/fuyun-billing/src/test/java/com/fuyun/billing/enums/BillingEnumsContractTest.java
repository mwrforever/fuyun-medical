package com.fuyun.billing.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 枚举契约测试（A.2-7 code↔enum 双向映射；DB 列值域与 V600–V604 注释一致性锚定） */
class BillingEnumsContractTest {

    @Test
    @DisplayName("费用状态机全集与 Spec §5 一致（含 P6/绿通预留值）")
    void feeStatusCoversSpecStateMachine() {
        assertThat(Arrays.stream(FeeStatus.values()).map(Enum::name))
                .containsExactly(
                        "PENDING",
                        "CONFIRMED",
                        "SETTLED",
                        "PART_REFUND",
                        "FULL_REFUND",
                        "CANCELLED",
                        "GUARANTEED",
                        "BAD_DEBT");
        assertThat(FeeStatus.fromCode("PENDING")).isEqualTo(FeeStatus.PENDING);
        assertThat(FeeStatus.PENDING.getCode()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("结算状态机全集与 Spec §5 一致")
    void settlementStatusCoversSpecStateMachine() {
        assertThat(Arrays.stream(SettlementStatus.values()).map(Enum::name))
                .containsExactly("DRAFT", "PRESETTLED", "SETTLED", "REFUNDED", "RED_REVERSED", "CANCELLED");
    }

    @Test
    @DisplayName("计价触发型七值与 Spec §4 pricing_rule 一致（DURATION 消费随 P2）")
    void triggerTypeCoversSevenTriggers() {
        assertThat(Arrays.stream(TriggerType.values()).map(Enum::name))
                .containsExactly(
                        "ORDER_CONFIRMED",
                        "PRESCRIPTION_EFFECTIVE",
                        "EXECUTED",
                        "REGISTERED",
                        "SCANNED",
                        "DURATION",
                        "MANUAL");
    }
}
