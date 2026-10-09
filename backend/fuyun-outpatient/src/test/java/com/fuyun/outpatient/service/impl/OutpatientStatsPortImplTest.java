package com.fuyun.outpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.outpatient.api.OutpatientWorkloadStats;
import com.fuyun.outpatient.api.OutpatientWorkloadStats.DailyVisitTrendRow;
import com.fuyun.outpatient.api.TrendWindow;
import com.fuyun.outpatient.entity.QueueTicket;
import com.fuyun.outpatient.mapper.QueueTicketMapper;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.outpatient.mapper.VisitStatsMapper;
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
 * 门诊工作量统计端口实现单测（批次 2 册 2）：①三段聚合（当日计数/候诊面/趋势零填充）与
 * 出参映射；②按科室候诊表 JVM 聚合（计数降序、最长等待分钟取最早 queue_time、空 queue_time
 * 防御）；③趋势窗口倒置守卫（TrendWindow 紧凑构造器契约）；④空数据零值视图。MP 3.5.17
 * 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息。JaCoCo outpatient.service.impl
 * 1.00 行覆盖红线：本类承载 OutpatientStatsPortImpl 全部分支面。
 */
@ExtendWith(MockitoExtension.class)
class OutpatientStatsPortImplTest {

    /** 统计窗口：近 3 日（零填充断言可枚举） */
    private static final TrendWindow WINDOW = new TrendWindow(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 9));

    @Mock
    private VisitMapper visitMapper;

    @Mock
    private QueueTicketMapper queueTicketMapper;

    @Mock
    private VisitStatsMapper visitStatsMapper;

    private OutpatientStatsPortImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（就诊/票据两面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QueueTicket.class);
    }

    @BeforeEach
    void setUp() {
        service = new OutpatientStatsPortImpl(visitMapper, queueTicketMapper, visitStatsMapper);
    }

    @Test
    @DisplayName("①三段聚合：当日计数+候诊计数+趋势零填充（缺日落零点，stat_date 升序稳定出点）")
    void workloadStatsAggregatesThreeSegmentsWithZeroFill() {
        when(visitMapper.selectCount(any())).thenReturn(128L);
        when(queueTicketMapper.selectCount(any())).thenReturn(23L);
        when(queueTicketMapper.selectList(any())).thenReturn(List.of());
        // 聚合行仅覆盖窗口中一日，其余两日应零填充
        when(visitStatsMapper.sumDailyVisits(WINDOW.fromDate(), WINDOW.toDate()))
                .thenReturn(List.of(new DailyVisitTrendRow(LocalDate.of(2026, 10, 8), 120L, 8L)));

        OutpatientWorkloadStats stats = service.workloadStats(WINDOW);

        assertThat(stats.window()).isEqualTo(WINDOW);
        assertThat(stats.todayVisits()).isEqualTo(128L);
        assertThat(stats.waitingCount()).isEqualTo(23L);
        assertThat(stats.trend()).hasSize(3);
        assertThat(stats.trend())
                .extracting(DailyVisitTrendRow::statDate)
                .containsExactly(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9));
        assertThat(stats.trend().get(0).visitCount()).isZero();
        assertThat(stats.trend().get(1).visitCount()).isEqualTo(120L);
        assertThat(stats.trend().get(1).emergencyCount()).isEqualTo(8L);
        assertThat(stats.trend().get(2).emergencyCount()).isZero();
        assertThat(stats.waitingByDept()).isEmpty();
    }

    @Test
    @DisplayName("②按科室候诊表：计数降序+最长等待分钟取最早 queue_time 向下取整")
    void waitingByDeptSortedByCountWithLongestWaitingMinutes() {
        when(visitMapper.selectCount(any())).thenReturn(0L);
        when(queueTicketMapper.selectCount(any())).thenReturn(0L);
        // 两科室候诊行：D02 三行（最早等待 90 分钟前）、D01 一行（等待 30 分钟前）
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        QueueTicket d02Early = waitingTicket("D02", now.minusMinutes(90));
        QueueTicket d02Mid = waitingTicket("D02", now.minusMinutes(40));
        QueueTicket d02Late = waitingTicket("D02", now.minusMinutes(10));
        QueueTicket d01 = waitingTicket("D01", now.minusMinutes(30));
        when(queueTicketMapper.selectList(any())).thenReturn(List.of(d02Mid, d01, d02Late, d02Early));

        OutpatientWorkloadStats stats = service.workloadStats(WINDOW);

        // 候诊人数降序：D02(3) 在前、D01(1) 在后；最长等待取各科最早 queue_time（容忍±1 分钟取整抖动）
        assertThat(stats.waitingByDept()).hasSize(2);
        assertThat(stats.waitingByDept().get(0).deptCode()).isEqualTo("D02");
        assertThat(stats.waitingByDept().get(0).waitingCount()).isEqualTo(3);
        assertThat(stats.waitingByDept().get(0).longestWaitingMinutes()).isBetween(89L, 91L);
        assertThat(stats.waitingByDept().get(1).deptCode()).isEqualTo("D01");
        assertThat(stats.waitingByDept().get(1).longestWaitingMinutes()).isBetween(29L, 31L);
    }

    @Test
    @DisplayName("③空 queue_time 防御：该科等待分钟兜底 0 不抛 NPE（时钟异常行不破聚合）")
    void waitingByDeptToleratesNullQueueTime() {
        when(visitMapper.selectCount(any())).thenReturn(0L);
        when(queueTicketMapper.selectCount(any())).thenReturn(0L);
        QueueTicket broken = waitingTicket("D09", null);
        when(queueTicketMapper.selectList(any())).thenReturn(List.of(broken));

        OutpatientWorkloadStats stats = service.workloadStats(WINDOW);

        assertThat(stats.waitingByDept()).hasSize(1);
        assertThat(stats.waitingByDept().get(0).deptCode()).isEqualTo("D09");
        assertThat(stats.waitingByDept().get(0).waitingCount()).isEqualTo(1);
        assertThat(stats.waitingByDept().get(0).longestWaitingMinutes()).isZero();
    }

    @Test
    @DisplayName("④空数据零值视图：三 mapper 全零返回零值/空清单，不造数")
    void workloadStatsReturnsZeroViewOnEmptyData() {
        when(visitMapper.selectCount(any())).thenReturn(null);
        when(queueTicketMapper.selectCount(any())).thenReturn(null);
        when(queueTicketMapper.selectList(any())).thenReturn(List.of());
        when(visitStatsMapper.sumDailyVisits(WINDOW.fromDate(), WINDOW.toDate()))
                .thenReturn(List.of());

        OutpatientWorkloadStats stats = service.workloadStats(WINDOW);

        assertThat(stats.todayVisits()).isZero();
        assertThat(stats.waitingCount()).isZero();
        assertThat(stats.waitingByDept()).isEmpty();
        assertThat(stats.trend()).hasSize(3);
        assertThat(stats.trend()).allSatisfy(row -> {
            assertThat(row.visitCount()).isZero();
            assertThat(row.emergencyCount()).isZero();
        });
    }

    @Test
    @DisplayName("⑤窗口倒置守卫：TrendWindow 紧凑构造器拒绝 fromDate 晚于 toDate")
    void trendWindowRejectsInvertedRange() {
        assertThatThrownBy(() -> new TrendWindow(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromDate");
        assertThatThrownBy(() -> new TrendWindow(null, LocalDate.of(2026, 10, 9)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 构造 WAITING 候诊票据行（仅分组两列有值——精确投影面）。
     *
     * @param deptCode  科室编码
     * @param queueTime 排队时刻（可空——防御分支用例）
     * @return 票据行
     */
    private QueueTicket waitingTicket(String deptCode, OffsetDateTime queueTime) {
        QueueTicket ticket = new QueueTicket();
        ticket.setQueueId(deptCode);
        ticket.setQueueTime(queueTime);
        return ticket;
    }
}
