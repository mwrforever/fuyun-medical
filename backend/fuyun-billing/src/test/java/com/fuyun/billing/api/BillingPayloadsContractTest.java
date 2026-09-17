package com.fuyun.billing.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CF-4 六事件载荷 record 契约测试：组件存取、等值/哈希、toString 生成物可用——
 * 事件体经 EventEnvelopeCodec 序列化前构造锚定（载荷字段见 V605 event_registry 种子）。
 */
class BillingPayloadsContractTest {

    @Test
    @DisplayName("FeeCreatedPayload：九组件存取与等值/哈希/toString 契约")
    void feeCreatedPayloadContract() {
        FeeCreatedPayload payload = new FeeCreatedPayload(
                1L, "FEE20260917001", 5L, "V20260917000001", 7L, "血常规", 1230L, "ORDER_LINKED", "billing-key-1");
        FeeCreatedPayload same = new FeeCreatedPayload(
                1L, "FEE20260917001", 5L, "V20260917000001", 7L, "血常规", 1230L, "ORDER_LINKED", "billing-key-1");

        assertThat(payload.feeId()).isEqualTo(1L);
        assertThat(payload.billingKey()).isEqualTo("billing-key-1");
        assertThat(payload).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(payload.toString()).contains("FEE20260917001").contains("billing-key-1");
    }

    @Test
    @DisplayName("FeeConfirmedPayload：四组件存取与等值/哈希/toString 契约")
    void feeConfirmedPayloadContract() {
        FeeConfirmedPayload payload = new FeeConfirmedPayload(1L, 5L, "V20260917000001", 1230L);
        FeeConfirmedPayload same = new FeeConfirmedPayload(1L, 5L, "V20260917000001", 1230L);

        assertThat(payload.amount()).isEqualTo(1230L);
        assertThat(payload).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(payload.toString()).contains("V20260917000001");
    }

    @Test
    @DisplayName("SettlementCompletedPayload：十组件存取与等值/哈希/toString 契约")
    void settlementCompletedPayloadContract() {
        SettlementCompletedPayload payload = new SettlementCompletedPayload(
                2L, "SET20260917001", 5L, "V20260917000001", "OUT", "SELF_PAY", 5000L, 0L, 3000L, 2000L);
        SettlementCompletedPayload same = new SettlementCompletedPayload(
                2L, "SET20260917001", 5L, "V20260917000001", "OUT", "SELF_PAY", 5000L, 0L, 3000L, 2000L);

        assertThat(payload.totalAmount()).isEqualTo(5000L);
        assertThat(payload.acctPayAmount()).isEqualTo(3000L);
        assertThat(payload).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(payload.toString()).contains("SET20260917001");
    }

    @Test
    @DisplayName("RefundApprovedPayload：七组件（含 autoApproved 原生布尔）存取与等值/哈希/toString 契约")
    void refundApprovedPayloadContract() {
        RefundApprovedPayload payload =
                new RefundApprovedPayload(3L, "REF20260917001", 2L, 5L, 1230L, "SETTLED_REFUND", true);
        RefundApprovedPayload same =
                new RefundApprovedPayload(3L, "REF20260917001", 2L, 5L, 1230L, "SETTLED_REFUND", true);

        assertThat(payload.refundNo()).isEqualTo("REF20260917001");
        assertThat(payload.autoApproved()).isTrue();
        assertThat(payload).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(payload.toString()).contains("REF20260917001");
    }

    @Test
    @DisplayName("DepositChangedPayload：五组件存取与等值/哈希/toString 契约")
    void depositChangedPayloadContract() {
        DepositChangedPayload payload = new DepositChangedPayload(4L, 5L, "V20260917000001", 8000L, "NORMAL");
        DepositChangedPayload same = new DepositChangedPayload(4L, 5L, "V20260917000001", 8000L, "NORMAL");

        assertThat(payload.balance()).isEqualTo(8000L);
        assertThat(payload.status()).isEqualTo("NORMAL");
        assertThat(payload).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(payload.toString()).contains("8000");
    }

    @Test
    @DisplayName("ChargeItemPricePublishedPayload：五组件（含 Instant 生效时刻）存取与等值/哈希/toString 契约")
    void chargeItemPricePublishedPayloadContract() {
        ChargeItemPricePublishedPayload payload =
                new ChargeItemPricePublishedPayload(7L, "C001", 2, 1230L, Instant.parse("2026-09-17T00:00:00Z"));
        ChargeItemPricePublishedPayload same =
                new ChargeItemPricePublishedPayload(7L, "C001", 2, 1230L, Instant.parse("2026-09-17T00:00:00Z"));

        assertThat(payload.priceVersion()).isEqualTo(2);
        assertThat(payload.price()).isEqualTo(1230L);
        assertThat(payload).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(payload.toString()).contains("C001");
    }
}
