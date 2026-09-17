package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.dto.InsuranceMappingUpsertRequest;
import com.fuyun.billing.entity.InsuranceMapping;
import com.fuyun.billing.enums.InsurancePayType;
import com.fuyun.billing.enums.MapType;
import com.fuyun.billing.enums.MappingStatus;
import com.fuyun.billing.mapper.InsuranceMappingMapper;
import java.math.BigDecimal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 医保对照实现单测（FU-M13-01 贯标载体）：ACTIVE 对照查询（命中/无命中返 null）与
 * upsert 单 ACTIVE 行语义（存在 ACTIVE 行则改写、无则插新行）。
 */
@ExtendWith(MockitoExtension.class)
class InsuranceMappingServiceImplTest {

    @Mock
    private InsuranceMappingMapper insuranceMappingMapper;

    private InsuranceMappingServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 单测容器外手动初始化实体表信息（lambda 列名解析依赖 TableInfo，模块内既有 ServiceImpl 单测同款）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), InsuranceMapping.class);
    }

    @BeforeEach
    void setUp() {
        service = new InsuranceMappingServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", insuranceMappingMapper);
        ReflectionTestUtils.setField(service, "entityClass", InsuranceMapping.class);
    }

    /** 对照登记请求样例（乙类：先自付 10%、医保限价 500 元）；来源：物价员对照国家目录录入 */
    private InsuranceMappingUpsertRequest req() {
        return new InsuranceMappingUpsertRequest(
                5L,
                MapType.TREATMENT,
                "NHBZ-TREAT-001",
                "2026.0",
                new BigDecimal("0.1000"),
                50000L,
                InsurancePayType.CLASS_B,
                null);
    }

    @Test
    @DisplayName("ACTIVE 对照查询：命中 ACTIVE 返行、无命中返 null（两断言一用例）")
    void effectiveMappingHitsActiveOrReturnsNull() {
        InsuranceMapping active = new InsuranceMapping();
        active.setId(9L);
        active.setChargeItemId(5L);
        active.setStatus(MappingStatus.ACTIVE);
        // 同一 stub 链变参依次命中：第一次返 ACTIVE 行，第二次返 null（未贯标/已失效）
        when(insuranceMappingMapper.selectOne(any())).thenReturn(active, (InsuranceMapping) null);

        assertThat(service.effectiveMapping(5L)).isSameAs(active);
        assertThat(service.effectiveMapping(5L)).isNull();
    }

    @Test
    @DisplayName("对照登记 upsert：存在 ACTIVE 行则改该行（never insert），无则插新 ACTIVE 行回填 id")
    void upsertMappingReplacesActiveRow() {
        InsuranceMapping active = new InsuranceMapping();
        active.setId(7L);
        active.setChargeItemId(5L);
        active.setStatus(MappingStatus.ACTIVE);
        // 同一 stub 链变参依次命中：第一次命中 ACTIVE 行→改写分支，第二次 null→插入分支
        when(insuranceMappingMapper.selectOne(any())).thenReturn(active, (InsuranceMapping) null);
        // 模拟 MP ASSIGN_ID 插入期回填主键（生产参数处理器行为一致）
        when(insuranceMappingMapper.insert(any(InsuranceMapping.class))).thenAnswer(inv -> {
            inv.getArgument(0, InsuranceMapping.class).setId(8L);
            return 1;
        });

        // 存在即改分支：原 ACTIVE 行 id 保留，编码按最新对照改写
        assertThat(service.upsert(req())).isEqualTo(7L);
        ArgumentCaptor<InsuranceMapping> updated = ArgumentCaptor.forClass(InsuranceMapping.class);
        verify(insuranceMappingMapper).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(7L);
        assertThat(updated.getValue().getNhsaCode()).isEqualTo("NHBZ-TREAT-001");
        verify(insuranceMappingMapper, never()).insert(any(InsuranceMapping.class));

        // 无则插分支：新行落 ACTIVE 状态（部分唯一索引 uk_mapping_item_active 兜底唯一）
        assertThat(service.upsert(req())).isEqualTo(8L);
        ArgumentCaptor<InsuranceMapping> inserted = ArgumentCaptor.forClass(InsuranceMapping.class);
        verify(insuranceMappingMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo(MappingStatus.ACTIVE);
        assertThat(inserted.getValue().getChargeItemId()).isEqualTo(5L);
    }
}
