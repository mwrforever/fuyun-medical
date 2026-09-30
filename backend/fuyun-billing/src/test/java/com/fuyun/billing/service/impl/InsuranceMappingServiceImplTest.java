package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.dto.InsuranceMappingUpsertRequest;
import com.fuyun.billing.entity.InsuranceMapping;
import com.fuyun.billing.enums.InsurancePayType;
import com.fuyun.billing.enums.MapType;
import com.fuyun.billing.enums.MappingStatus;
import com.fuyun.billing.mapper.InsuranceMappingMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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

        // 查询 SQL 守卫钉死（Important 修复）：必须限定 charge_item_id 且携带 status=ACTIVE 等值参数——
        // ACTIVE 过滤是 Task 10 取价快照与 Task 12 贯标硬校验（BILL-1006）的数据前提，禁被静默删除
        ArgumentCaptor<Wrapper<InsuranceMapping>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(insuranceMappingMapper, times(2)).selectOne(wrapperCaptor.capture());
        for (Wrapper<InsuranceMapping> captured : wrapperCaptor.getAllValues()) {
            LambdaQueryWrapper<InsuranceMapping> wrapper = (LambdaQueryWrapper<InsuranceMapping>) captured;
            // 先渲染 SQL 片段：MP 条件参数在 getSqlSegment 惰性求值时才写入 paramNameValuePairs（integration 同款）
            assertThat(wrapper.getSqlSegment()).contains("charge_item_id").contains("status");
            assertThat(wrapper.getParamNameValuePairs().values()).contains(5L, MappingStatus.ACTIVE);
        }
    }

    @Test
    @DisplayName("ACTIVE 对照批查：一次 IN 批查按键返回，无 ACTIVE 行项目不出键，空键集零 SQL")
    void effectiveMappingsBatchesActiveRowsByKey() {
        InsuranceMapping active = new InsuranceMapping();
        active.setId(9L);
        active.setChargeItemId(5L);
        active.setStatus(MappingStatus.ACTIVE);
        // 项目 6 无 ACTIVE 行（未贯标/已失效）：不出键（与单查返 null 同口径）
        when(insuranceMappingMapper.selectList(any())).thenReturn(List.of(active));

        Map<Long, InsuranceMapping> result = service.effectiveMappings(List.of(5L, 6L));

        assertThat(result).containsOnlyKeys(5L);
        assertThat(result.get(5L)).isSameAs(active);
        // 批查 SQL 守卫钉死：IN 键集落在 charge_item_id 列 + status=ACTIVE 等值参数——
        // ACTIVE 过滤是取价快照对照腿的数据前提（与单查谓词同构，防静默删 ACTIVE 过滤）
        ArgumentCaptor<Wrapper<InsuranceMapping>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(insuranceMappingMapper, times(1)).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<InsuranceMapping> wrapper = (LambdaQueryWrapper<InsuranceMapping>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("charge_item_id").contains("status");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(5L, 6L, MappingStatus.ACTIVE);
        // 空键集零 SQL 触达（MP in 谓词空集生成非法 SQL，前置短路）
        assertThat(service.effectiveMappings(List.of())).isEmpty();
        verify(insuranceMappingMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("ACTIVE 对照批查遇重复 chargeItemId 脏数据：保留首行不抛异常（部分唯一索引防御兜底）")
    void effectiveMappingsKeepsFirstRowOnDuplicateChargeItemId() {
        // 脏数据场景：uk_mapping_item_active 部分唯一索引被绕过（历史数据/人工改库），
        // 批查结果同一 chargeItemId 返回两条 ACTIVE 行——无 merge 函数时 toMap 将抛
        // IllegalStateException 使整单快照取价失败，merge 语义为保留批查结果中的首行
        InsuranceMapping first = new InsuranceMapping();
        first.setId(9L);
        first.setChargeItemId(5L);
        first.setNhsaCode("NHBZ-TREAT-001");
        first.setStatus(MappingStatus.ACTIVE);
        InsuranceMapping duplicate = new InsuranceMapping();
        duplicate.setId(10L);
        duplicate.setChargeItemId(5L);
        duplicate.setNhsaCode("NHBZ-TREAT-002");
        duplicate.setStatus(MappingStatus.ACTIVE);
        when(insuranceMappingMapper.selectList(any())).thenReturn(List.of(first, duplicate));

        Map<Long, InsuranceMapping> result = service.effectiveMappings(List.of(5L));

        // 保留首行：键收敛为 1，值身份与字段均指向批查结果中的第一条 ACTIVE 行
        assertThat(result).containsOnlyKeys(5L);
        assertThat(result.get(5L)).isSameAs(first);
        assertThat(result.get(5L).getId()).isEqualTo(9L);
        assertThat(result.get(5L).getNhsaCode()).isEqualTo("NHBZ-TREAT-001");
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
