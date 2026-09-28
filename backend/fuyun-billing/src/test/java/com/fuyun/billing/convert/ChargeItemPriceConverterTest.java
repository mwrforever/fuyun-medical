package com.fuyun.billing.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.billing.entity.ChargeItemPrice;
import com.fuyun.billing.enums.PriceSource;
import com.fuyun.billing.enums.PriceStatus;
import com.fuyun.billing.vo.ChargeItemPriceVO;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 价格版本域转换器单测（BUG-23 迁入守护；消解 BE-C1-09）：实体→出参与原 ChargeItemPriceVO.from
 * 手写逐字段等价——全字段断言锁同名对位语义，并守护可空字段的 null 传播；单价 price（分）为
 * Long 同型直传零换算，取超 int 值域的分值断言 Long 全值透传禁数值化（转字符串归 Jackson 出网层，
 * 转换器不得提前数值化/截断），对应宪法 A.7-4 金额列单测全覆盖要求。
 */
class ChargeItemPriceConverterTest {

    @Test
    @DisplayName("价格版本直映：10 字段全量等价（单价取超 int 值域分值锁 Long 透传禁数值化）")
    void priceMapsAllFields() {
        ChargeItemPrice entity = new ChargeItemPrice();
        entity.setId(7101L);
        entity.setChargeItemId(7001L);
        // 987_654_321.01 元=9_876_543_210_101 分，超 int 值域：任何数值化/截断即断言失败
        entity.setPrice(9_876_543_210_101L);
        entity.setVersion(3);
        entity.setEffectiveFrom(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8)));
        entity.setEffectiveTo(OffsetDateTime.of(2026, 12, 31, 23, 59, 59, 0, ZoneOffset.ofHours(8)));
        entity.setPriceSource(PriceSource.OFFICIAL_DOC);
        entity.setApprovalNo("沪价〔2026〕118 号");
        entity.setStatus(PriceStatus.PUBLISHED);

        ChargeItemPriceVO vo = ChargeItemPriceConverter.INSTANCE.toVO(entity);

        assertThat(vo.getId()).isEqualTo(7101L);
        assertThat(vo.getChargeItemId()).isEqualTo(7001L);
        assertThat(vo.getPrice()).isEqualTo(9_876_543_210_101L);
        assertThat(vo.getVersion()).isEqualTo(3);
        assertThat(vo.getEffectiveFrom()).isEqualTo(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8)));
        assertThat(vo.getEffectiveTo())
                .isEqualTo(OffsetDateTime.of(2026, 12, 31, 23, 59, 59, 0, ZoneOffset.ofHours(8)));
        assertThat(vo.getPriceSource()).isEqualTo(PriceSource.OFFICIAL_DOC);
        assertThat(vo.getApprovalNo()).isEqualTo("沪价〔2026〕118 号");
        assertThat(vo.getStatus()).isEqualTo(PriceStatus.PUBLISHED);
    }

    @Test
    @DisplayName("价格版本 null 传播：当前有效版本无失效止、协议价无批文号均直传 null")
    void pricePropagatesNullables() {
        ChargeItemPrice entity = new ChargeItemPrice();
        entity.setId(7102L);
        entity.setChargeItemId(7001L);
        entity.setPrice(1_250L);
        entity.setVersion(4);
        entity.setEffectiveFrom(OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8)));
        entity.setPriceSource(PriceSource.AGREEMENT);
        entity.setStatus(PriceStatus.DRAFT);

        ChargeItemPriceVO vo = ChargeItemPriceConverter.INSTANCE.toVO(entity);

        // 原手写直传语义：effectiveTo null=当前有效版本、approvalNo null=协议价无批文号——不得转默认值
        assertThat(vo.getEffectiveTo()).isNull();
        assertThat(vo.getApprovalNo()).isNull();
        assertThat(vo.getPrice()).isEqualTo(1_250L);
        assertThat(vo.getStatus()).isEqualTo(PriceStatus.DRAFT);
    }
}
