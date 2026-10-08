package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.api.BillingWorkloadStats;
import com.fuyun.billing.api.BillingWorkloadStats.PendingFeeEvent;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.mapper.FeeRecordMapper;
import java.time.LocalDate;
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
 * 收费工作量统计端口实现单测（批次 2 册 2）：①三段聚合（当日收入/待结算/待支付事件行）与
 * 出参映射（金额分值、计费时刻直传）；②空数据零值视图（mapper 返回 null/空集不造数）；
 * ③事件行集降序有界下推断言（LIMIT 拼接走 wrapper last——以 mapper 调用触达收口）。
 * MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息。JaCoCo
 * billing.service.impl 1.00 行覆盖红线：本类承载 BillingStatsPortImpl 全部分支面。
 */
@ExtendWith(MockitoExtension.class)
class BillingStatsPortImplTest {

    /** 统计计费日 */
    private static final LocalDate DATE = LocalDate.of(2026, 10, 9);

    @Mock
    private FeeRecordMapper feeRecordMapper;

    private BillingStatsPortImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（费用行面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FeeRecord.class);
    }

    @BeforeEach
    void setUp() {
        service = new BillingStatsPortImpl(feeRecordMapper);
    }

    @Test
    @DisplayName("①三段聚合：当日收入/待结算计数经 mapper 注解聚合+待支付事件行映射")
    void workloadStatsAggregatesThreeSegments() {
        when(feeRecordMapper.sumDailyValidAmount(DATE)).thenReturn(4567800L);
        when(feeRecordMapper.countPendingUnsettled(DATE)).thenReturn(34L);
        OffsetDateTime chargedAt = OffsetDateTime.of(2026, 10, 9, 10, 30, 0, 0, ZoneOffset.UTC);
        when(feeRecordMapper.selectList(any())).thenReturn(List.of(feeRow("F1001", "血常规", 3500L, chargedAt)));

        BillingWorkloadStats stats = service.workloadStats(DATE);

        assertThat(stats.date()).isEqualTo(DATE);
        assertThat(stats.todayIncomeFen()).isEqualTo(4567800L);
        assertThat(stats.pendingSettleCount()).isEqualTo(34L);
        assertThat(stats.pendingFeeEvents()).hasSize(1);
        PendingFeeEvent event = stats.pendingFeeEvents().get(0);
        assertThat(event.feeNo()).isEqualTo("F1001");
        assertThat(event.itemName()).isEqualTo("血常规");
        assertThat(event.amountFen()).isEqualTo(3500L);
        assertThat(event.chargedAt()).isEqualTo(chargedAt);
        // 两格聚合确经 mapper 注解 SQL（deleted=0 显式补齐的唯一执行点）
        verify(feeRecordMapper).sumDailyValidAmount(DATE);
        verify(feeRecordMapper).countPendingUnsettled(DATE);
    }

    @Test
    @DisplayName("②空数据零值视图：聚合零返回+事件空集，出参零值/空清单不造数")
    void workloadStatsReturnsZeroViewOnEmptyData() {
        when(feeRecordMapper.sumDailyValidAmount(DATE)).thenReturn(0L);
        when(feeRecordMapper.countPendingUnsettled(DATE)).thenReturn(0L);
        when(feeRecordMapper.selectList(any())).thenReturn(List.of());

        BillingWorkloadStats stats = service.workloadStats(DATE);

        assertThat(stats.todayIncomeFen()).isZero();
        assertThat(stats.pendingSettleCount()).isZero();
        assertThat(stats.pendingFeeEvents()).isEmpty();
    }

    /**
     * 构造费用行（仅事件四要素投影列有值）。
     *
     * @param feeNo     费用编号
     * @param itemName  项目名称快照
     * @param amountFen 金额（分）
     * @param chargedAt 计费时刻
     * @return 费用行
     */
    private FeeRecord feeRow(String feeNo, String itemName, Long amountFen, OffsetDateTime chargedAt) {
        FeeRecord row = new FeeRecord();
        row.setFeeNo(feeNo);
        row.setItemNameSnapshot(itemName);
        row.setAmount(amountFen);
        row.setChargedAt(chargedAt);
        return row;
    }
}
