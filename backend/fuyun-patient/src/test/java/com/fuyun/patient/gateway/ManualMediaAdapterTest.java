package com.fuyun.patient.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 手工证件核实兜底适配器单测：录入证件号即人工核实通过（实名），未录入返回未实名——
 * 覆盖实名/未实名两条判定路径（gateway 包计 BUNDLE 覆盖口径）。
 */
class ManualMediaAdapterTest {

    private final ManualMediaAdapter adapter = new ManualMediaAdapter();

    @Test
    @DisplayName("录入证件号：视同人工核实通过，标识类型与原文回传交加密层")
    void verifyWithIdCardNumberTreatsAsRealNameVerified() {
        IdentityMediaGateway.IdentityExtract extract = adapter.verify("ID_CARD", "110101199003077890");
        assertThat(extract.verified()).isTrue();
        assertThat(extract.realName()).isTrue();
        assertThat(extract.identifierType()).isEqualTo("ID_CARD");
        assertThat(extract.identifierValue()).isEqualTo("110101199003077890");
        assertThat(extract.cardNo()).isNull();
    }

    @Test
    @DisplayName("未录入证件号（含空白串）：返回未实名（授权建档路径，未实名标记必落）")
    void verifyWithoutIdCardNumberMarksUnverified() {
        assertThat(adapter.verify("ID_CARD", null).verified()).isFalse();
        IdentityMediaGateway.IdentityExtract blank = adapter.verify("PASSPORT", "   ");
        assertThat(blank.verified()).isFalse();
        assertThat(blank.realName()).isFalse();
        assertThat(blank.identifierType()).isEqualTo("PASSPORT");
    }
}
