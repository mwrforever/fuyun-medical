package com.fuyun.system.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.system.entity.DictItemEntity;
import com.fuyun.system.entity.DictTypeEntity;
import com.fuyun.system.entity.DictVersionEntity;
import com.fuyun.system.enums.DictVersionStatus;
import com.fuyun.system.vo.DictVersionVO;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 字典域 MapStruct 转换器测试（映射契约：多源聚合与跨表字段映射，A.7-4 关键映射单测）。
 */
class DictConverterTest {

    /** 转换器实例：与 SystemWebConfig 装配同源（Mappers.getMapper 取生成实现） */
    private final DictConverter converter = DictConverter.INSTANCE;

    @Test
    @DisplayName("版本出参聚合：typeCode 取自类型表、状态/发布时刻取自版本表、条目清单透传")
    void toVersionVoAggregatesTypeAndVersionAndItems() {
        DictTypeEntity type = new DictTypeEntity();
        type.setId(8001L);
        type.setTypeCode("gender");
        DictVersionEntity version = new DictVersionEntity();
        version.setId(8101L);
        version.setDictTypeId(8001L);
        version.setVersion(2);
        version.setStatus(DictVersionStatus.PUBLISHED);
        version.setPublishedAt(OffsetDateTime.parse("2026-09-09T08:00:00+00:00"));
        DictItemEntity item = new DictItemEntity();
        item.setItemCode("M");
        item.setItemName("男");
        item.setSort(1);

        DictVersionVO vo = converter.toVersionVO(type, version, List.of(converter.toItemVO(item)));

        assertThat(vo.typeCode()).isEqualTo("gender");
        assertThat(vo.version()).isEqualTo(2);
        assertThat(vo.status()).isEqualTo(DictVersionStatus.PUBLISHED);
        assertThat(vo.publishedAt()).isEqualTo(OffsetDateTime.parse("2026-09-09T08:00:00+00:00"));
        assertThat(vo.items()).hasSize(1);
        assertThat(vo.items().get(0).itemCode()).isEqualTo("M");
        // ext_attrs 列 P0 不出参：条目出参无该字段（record 分量全集断言）
        assertThat(vo.items().get(0)).hasOnlyFields("itemCode", "itemName", "parentCode", "sort");
    }
}
