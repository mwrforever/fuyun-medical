package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.pharmacy.api.PharmacyWorkloadStats;
import com.fuyun.pharmacy.api.PharmacyWorkloadStats.PendingDispenseEvent;
import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.mapper.DispenseMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 药事工作量统计端口实现单测（批次 2 册 2）：①两段聚合（在途计数/待配药事件行）与出参映射；
 * ②空数据零值视图（计数 null 兜底零、事件空清单）。MP 3.5.17 单测范式：lambdaQuery 触达
 * 实体 @BeforeAll 手工注册表信息。JaCoCo pharmacy.service.impl 1.00 行覆盖红线：本类承载
 * PharmacyStatsPortImpl 全部分支面。
 */
@ExtendWith(MockitoExtension.class)
class PharmacyStatsPortImplTest {

    @Mock
    private DispenseMapper dispenseMapper;

    private PharmacyStatsPortImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（调剂单面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Dispense.class);
    }

    @BeforeEach
    void setUp() {
        service = new PharmacyStatsPortImpl(dispenseMapper);
    }

    @Test
    @DisplayName("①两段聚合：在途单计数+待配药事件行映射（单号/处方号/建单时刻直传）")
    void pendingStatsAggregatesCountAndEvents() {
        when(dispenseMapper.selectCount(any())).thenReturn(12L);
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 10, 9, 9, 15, 0, 0, ZoneOffset.UTC);
        when(dispenseMapper.selectList(any()))
                .thenReturn(List.of(dispenseRow("D1000001", "RX202610090001", createdAt)));

        PharmacyWorkloadStats stats = service.pendingStats();

        assertThat(stats.pendingDispenseCount()).isEqualTo(12L);
        assertThat(stats.pendingDispenseEvents()).hasSize(1);
        PendingDispenseEvent event = stats.pendingDispenseEvents().get(0);
        assertThat(event.dispenseNo()).isEqualTo("D1000001");
        assertThat(event.rxNo()).isEqualTo("RX202610090001");
        assertThat(event.createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("②空数据零值视图：计数 null 兜底零+事件空清单，不造数")
    void pendingStatsReturnsZeroViewOnEmptyData() {
        when(dispenseMapper.selectCount(any())).thenReturn(null);
        when(dispenseMapper.selectList(any())).thenReturn(List.of());

        PharmacyWorkloadStats stats = service.pendingStats();

        assertThat(stats.pendingDispenseCount()).isZero();
        assertThat(stats.pendingDispenseEvents()).isEmpty();
    }

    /**
     * 构造调剂单行（仅事件三要素投影列有值）。
     *
     * @param dispenseNo 调剂单号
     * @param rxNo       处方号
     * @param createdAt  建单时刻
     * @return 调剂单行
     */
    private Dispense dispenseRow(String dispenseNo, String rxNo, OffsetDateTime createdAt) {
        Dispense row = new Dispense();
        row.setDispenseNo(dispenseNo);
        row.setRxNo(rxNo);
        row.setCreatedAt(createdAt);
        return row;
    }
}
