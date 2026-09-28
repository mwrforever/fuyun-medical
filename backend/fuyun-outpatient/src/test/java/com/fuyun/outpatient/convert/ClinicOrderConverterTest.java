package com.fuyun.outpatient.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.outpatient.entity.ClinicOrder;
import com.fuyun.outpatient.entity.ClinicOrderItem;
import com.fuyun.outpatient.enums.OrderStatus;
import com.fuyun.outpatient.enums.OrderType;
import com.fuyun.outpatient.vo.ClinicOrderVO;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 申请单域转换器单测（BUG-20 迁入守护）：主单+明细行→出参直映与原 ClinicOrderServiceImpl 手写
 * 逐字段等价——全字段断言锁组件名对位语义（record 位置参数增删/调序在此即失败），并守护 DECIMAL
 * string 透传（quantity 禁数值化，D-18）与可空字段/空明细清单的 null 及空集合传播语义。
 */
class ClinicOrderConverterTest {

    @Test
    @DisplayName("申请单直映：11 主单字段全量等价 + 明细行 3 字段逐行等价（quantity string 透传）")
    void orderMapsAllFieldsWithItems() {
        ClinicOrder order = new ClinicOrder();
        order.setId(8001L);
        order.setOrderNo("OP20260928000001");
        order.setVisitId("O2026092800006");
        order.setPatientId(77L);
        order.setOrderType(OrderType.LAB);
        order.setExtRef("RX20260928000003");
        order.setOrderDoctorId("doc-0042");
        order.setValidTo(OffsetDateTime.parse("2026-10-05T23:59:59+08:00"));
        order.setStatus(OrderStatus.CHARGED);
        order.setDispenseStatus("DISPENSED");
        order.setFeeSettlementId(66001L);

        ClinicOrderItem row1 = new ClinicOrderItem();
        row1.setItemCode("LAB-001");
        row1.setQuantity("2");
        row1.setUsageSummary("每日一次，静脉采血");
        ClinicOrderItem row2 = new ClinicOrderItem();
        row2.setItemCode("LAB-014");
        row2.setQuantity("0.5");
        row2.setUsageSummary(null);

        ClinicOrderVO vo = ClinicOrderConverter.INSTANCE.toClinicOrderVO(order, List.of(row1, row2));

        assertThat(vo.id()).isEqualTo(8001L);
        assertThat(vo.orderNo()).isEqualTo("OP20260928000001");
        assertThat(vo.visitId()).isEqualTo("O2026092800006");
        assertThat(vo.patientId()).isEqualTo(77L);
        assertThat(vo.orderType()).isEqualTo(OrderType.LAB);
        assertThat(vo.extRef()).isEqualTo("RX20260928000003");
        assertThat(vo.orderDoctorId()).isEqualTo("doc-0042");
        assertThat(vo.validTo()).isEqualTo(OffsetDateTime.parse("2026-10-05T23:59:59+08:00"));
        assertThat(vo.status()).isEqualTo(OrderStatus.CHARGED);
        assertThat(vo.dispenseStatus()).isEqualTo("DISPENSED");
        assertThat(vo.feeSettlementId()).isEqualTo(66001L);
        // 明细行逐行等价：数量 DECIMAL string 原样承载（"0.5" 不得数值化漂移），可空用法摘要直传
        assertThat(vo.items()).hasSize(2);
        assertThat(vo.items().get(0).itemCode()).isEqualTo("LAB-001");
        assertThat(vo.items().get(0).quantity()).isEqualTo("2");
        assertThat(vo.items().get(0).usageSummary()).isEqualTo("每日一次，静脉采血");
        assertThat(vo.items().get(1).itemCode()).isEqualTo("LAB-014");
        assertThat(vo.items().get(1).quantity()).isEqualTo("0.5");
        assertThat(vo.items().get(1).usageSummary()).isNull();
    }

    @Test
    @DisplayName("申请单 null 传播：P1 未接执行域/未发药/未结算可空字段直传 null，RX_REF 引用行空明细")
    void orderPropagatesNullablesAndEmptyItems() {
        ClinicOrder order = new ClinicOrder();
        order.setId(8002L);
        order.setOrderNo("OP20260928000002");
        order.setVisitId("O2026092800007");
        order.setPatientId(78L);
        order.setOrderType(OrderType.RX_REF);
        order.setOrderDoctorId("doc-0043");
        order.setStatus(OrderStatus.CREATED);

        ClinicOrderVO vo = ClinicOrderConverter.INSTANCE.toClinicOrderVO(order, List.of());

        // 原手写直传语义：P1 执行域未接入（validTo 空）、非发药行（dispenseStatus 空）、未结算
        // （feeSettlementId 空）、RX_REF 引用行零明细（空清单不得转 null）
        assertThat(vo.extRef()).isNull();
        assertThat(vo.validTo()).isNull();
        assertThat(vo.dispenseStatus()).isNull();
        assertThat(vo.feeSettlementId()).isNull();
        assertThat(vo.items()).isEmpty();
    }
}
