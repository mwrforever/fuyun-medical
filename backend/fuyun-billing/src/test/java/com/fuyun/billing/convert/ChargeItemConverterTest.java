package com.fuyun.billing.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.billing.entity.ChargeItem;
import com.fuyun.billing.enums.ItemClass;
import com.fuyun.billing.enums.ItemPriceFlag;
import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.vo.ChargeItemVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 收费项目库域转换器单测（BUG-23 迁入守护；消解 BE-C1-09）：实体→出参与原 ChargeItemVO.from
 * 手写逐字段等价——全字段断言锁同名对位语义（字段增删/调名在此即失败，管理面字段演进漏映射即被
 * 拦截），并守护可空字段的 null 传播（原手写直传 null 语义）。
 */
class ChargeItemConverterTest {

    @Test
    @DisplayName("项目直映：10 字段全量等价（含类别/收费标记/状态枚举同型承载）")
    void itemMapsAllFields() {
        ChargeItem entity = new ChargeItem();
        entity.setId(7001L);
        entity.setItemCode("M700001");
        entity.setItemName("静脉输液");
        entity.setItemClass(ItemClass.WEST_DRUG);
        entity.setUnit("次");
        entity.setExecDeptId(30010L);
        entity.setPriceFlag(ItemPriceFlag.SINGLE);
        entity.setComboFlag(false);
        entity.setFeeCategory("DRUG");
        entity.setStatus(ItemStatus.ACTIVE);

        ChargeItemVO vo = ChargeItemConverter.INSTANCE.toVO(entity);

        assertThat(vo.getId()).isEqualTo(7001L);
        assertThat(vo.getItemCode()).isEqualTo("M700001");
        assertThat(vo.getItemName()).isEqualTo("静脉输液");
        assertThat(vo.getItemClass()).isEqualTo(ItemClass.WEST_DRUG);
        assertThat(vo.getUnit()).isEqualTo("次");
        assertThat(vo.getExecDeptId()).isEqualTo(30010L);
        assertThat(vo.getPriceFlag()).isEqualTo(ItemPriceFlag.SINGLE);
        assertThat(vo.getComboFlag()).isFalse();
        assertThat(vo.getFeeCategory()).isEqualTo("DRUG");
        assertThat(vo.getStatus()).isEqualTo(ItemStatus.ACTIVE);
    }

    @Test
    @DisplayName("项目 null 传播：未配置默认执行科室时直传 null 不转默认值")
    void itemPropagatesNullables() {
        ChargeItem entity = new ChargeItem();
        entity.setId(7002L);
        entity.setItemCode("M700002");
        entity.setItemName("留观床位费");
        entity.setItemClass(ItemClass.TREATMENT);
        entity.setUnit("日");
        entity.setPriceFlag(ItemPriceFlag.SINGLE);
        entity.setComboFlag(false);
        entity.setFeeCategory("BED");
        entity.setStatus(ItemStatus.ACTIVE);

        ChargeItemVO vo = ChargeItemConverter.INSTANCE.toVO(entity);

        // 原手写直传语义：execDeptId null=未配置默认执行科室——不得转默认值
        assertThat(vo.getExecDeptId()).isNull();
        assertThat(vo.getItemCode()).isEqualTo("M700002");
        assertThat(vo.getStatus()).isEqualTo(ItemStatus.ACTIVE);
    }
}
