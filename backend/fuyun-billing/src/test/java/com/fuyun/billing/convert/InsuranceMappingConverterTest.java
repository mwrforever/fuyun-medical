package com.fuyun.billing.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.billing.entity.InsuranceMapping;
import com.fuyun.billing.enums.InsurancePayType;
import com.fuyun.billing.enums.MapType;
import com.fuyun.billing.enums.MappingStatus;
import com.fuyun.billing.vo.InsuranceMappingVO;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 医保对照域转换器单测（BUG-23 迁入守护；消解 BE-C1-09）：实体→出参与原 InsuranceMappingVO.from
 * 手写逐字段等价——全字段断言锁同名对位语义，并守护可空字段的 null 传播；医保限价 limitPrice
 * （分）为 Long 同型直传零换算（取超 int 值域分值断言全值透传禁数值化）、先自付比例 selfPayRatio
 * 为 BigDecimal 同型直传保精度（scale 原样），对应宪法 A.7-4 金额/比例列单测全覆盖要求。
 */
class InsuranceMappingConverterTest {

    @Test
    @DisplayName("医保对照直映：10 字段全量等价（限价超 int 值域锁 Long 透传、比例保 DECIMAL(5,4) 精度）")
    void mappingMapsAllFields() {
        InsuranceMapping entity = new InsuranceMapping();
        entity.setId(7201L);
        entity.setChargeItemId(7001L);
        entity.setMapType(MapType.TREATMENT);
        entity.setNhsaCode("330100012000");
        entity.setCatalogVersion("2026A");
        entity.setSelfPayRatio(new BigDecimal("0.5000"));
        // 98_765.43 元=9_876_543 分，超 int 值域：任何数值化/截断即断言失败
        entity.setLimitPrice(9_876_543L);
        entity.setInsurancePayType(InsurancePayType.CLASS_A);
        entity.setStatus(MappingStatus.ACTIVE);
        entity.setCheckReceipt("贯标校验通过：22 项编码齐备");

        InsuranceMappingVO vo = InsuranceMappingConverter.INSTANCE.toVO(entity);

        assertThat(vo.getId()).isEqualTo(7201L);
        assertThat(vo.getChargeItemId()).isEqualTo(7001L);
        assertThat(vo.getMapType()).isEqualTo(MapType.TREATMENT);
        assertThat(vo.getNhsaCode()).isEqualTo("330100012000");
        assertThat(vo.getCatalogVersion()).isEqualTo("2026A");
        assertThat(vo.getSelfPayRatio()).isEqualTo(new BigDecimal("0.5000"));
        assertThat(vo.getLimitPrice()).isEqualTo(9_876_543L);
        assertThat(vo.getInsurancePayType()).isEqualTo(InsurancePayType.CLASS_A);
        assertThat(vo.getStatus()).isEqualTo(MappingStatus.ACTIVE);
        assertThat(vo.getCheckReceipt()).isEqualTo("贯标校验通过：22 项编码齐备");
    }

    @Test
    @DisplayName("医保对照 null 传播：目录外无限价且未校验回执均直传 null")
    void mappingPropagatesNullables() {
        InsuranceMapping entity = new InsuranceMapping();
        entity.setId(7202L);
        entity.setChargeItemId(7002L);
        entity.setMapType(MapType.CONSUMABLE);
        entity.setNhsaCode("330200007000");
        entity.setCatalogVersion("2026A");
        entity.setSelfPayRatio(BigDecimal.ONE);
        entity.setInsurancePayType(InsurancePayType.SELF_EXPENSE);
        entity.setStatus(MappingStatus.EXPIRED);

        InsuranceMappingVO vo = InsuranceMappingConverter.INSTANCE.toVO(entity);

        // 原手写直传语义：limitPrice null=无限价、checkReceipt null=未产生校验回执——不得转默认值
        assertThat(vo.getLimitPrice()).isNull();
        assertThat(vo.getCheckReceipt()).isNull();
        assertThat(vo.getSelfPayRatio()).isEqualTo(BigDecimal.ONE);
        assertThat(vo.getStatus()).isEqualTo(MappingStatus.EXPIRED);
    }
}
