package com.fuyun.outpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.outpatient.api.OutpatientStatsPort;
import com.fuyun.outpatient.api.OutpatientWorkloadStats;
import com.fuyun.outpatient.api.OutpatientWorkloadStats.DailyVisitTrendRow;
import com.fuyun.outpatient.api.OutpatientWorkloadStats.DeptWaitingRow;
import com.fuyun.outpatient.api.TrendWindow;
import com.fuyun.outpatient.entity.QueueTicket;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.enums.TicketStatus;
import com.fuyun.outpatient.mapper.QueueTicketMapper;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.outpatient.mapper.VisitStatsMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 门诊工作量统计端口实现（OutpatientStatsPort 承载，M03 → M19 统计接口位；BillingAccountQueryPortImpl
 * 同款形态）：当日人次计数 + 候诊两格/候诊表 + 窗口趋势三段聚合。纯只读，零事务零写面。
 *
 * <p>聚合口径：当日人次=visit 当日挂号计数（北京钟面窗口，lambdaQuery @TableLogic 自动携带）；
 * 候诊面=queue_ticket WAITING 行集一次取回后 JVM 分组（当日候诊量有界，AdverseEventServiceImpl
 * .stats JVM 聚合同款先例，免 GROUP BY 二语句）；趋势=VisitStatsMapper.xml GROUP BY 聚合 +
 * service 零填充（窗口逐日出点含无数据日，稳定契约形态——前端图表免判空）。无状态单例；
 * 装配归 OutpatientWebConfig @Import；JaCoCo outpatient.service.impl 核心包 LINE=1.00 成员。
 */
public class OutpatientStatsPortImpl implements OutpatientStatsPort {

    /** 候诊表/趋势读取面（BaseMapper 链式能力） */
    private final VisitMapper visitMapper;

    /** 候诊票据 mapper：候诊计数与按科室候诊行集取数面 */
    private final QueueTicketMapper queueTicketMapper;

    /** 逐日趋势 GROUP BY 聚合 mapper（XML 承载） */
    private final VisitStatsMapper visitStatsMapper;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import，backend 宪法 B.1）。
     *
     * @param visitMapper        就诊记录 mapper，非空；当日人次计数取数面
     * @param queueTicketMapper  候诊票据 mapper，非空；候诊计数与候诊表行集取数面
     * @param visitStatsMapper   门诊统计 mapper，非空；逐日趋势聚合取数面
     */
    public OutpatientStatsPortImpl(
            VisitMapper visitMapper, QueueTicketMapper queueTicketMapper, VisitStatsMapper visitStatsMapper) {
        this.visitMapper = visitMapper;
        this.queueTicketMapper = queueTicketMapper;
        this.visitStatsMapper = visitStatsMapper;
    }

    /**
     * 门诊工作量三段聚合（只读）：当日计数 → 候诊面 → 窗口趋势（零填充）。
     *
     * @param window 趋势统计窗口，非空（record 紧凑构造器已守卫方向）
     * @return 工作量统计快照，非空；无业务数据返回零值/空清单视图
     */
    @Override
    public OutpatientWorkloadStats workloadStats(TrendWindow window) {
        long todayVisits = countTodayVisits();
        long waitingCount = countWaiting();
        List<DeptWaitingRow> waitingByDept = aggregateWaitingByDept();
        List<DailyVisitTrendRow> trend = aggregateTrend(window);
        return new OutpatientWorkloadStats(window, todayVisits, waitingCount, waitingByDept, trend);
    }

    /**
     * 当日门诊人次计数：registered_at 落北京钟面当日窗口（[当日 00:00, 次日 00:00)）的 visit 行数
     * （逻辑删行由 @TableLogic 自动排除）。数据库读操作（COUNT 聚合）。
     *
     * @return 当日人次；无行返回 0
     */
    private long countTodayVisits() {
        // 统计日缺省北京钟面当日；窗口端点偏移照 AdverseEventServiceImpl.stats 先例取当前有效偏移
        LocalDate today = LocalDate.now(TimeConstants.HEALTHCARE_TZ);
        ZoneOffset offset = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ).getOffset();
        OffsetDateTime windowStart = today.atStartOfDay().atOffset(offset);
        OffsetDateTime windowEnd = windowStart.plusDays(1);
        // 数据库读操作：当日挂号计数（窗口左闭右开，索引友好）
        Long count = visitMapper.selectCount(Wrappers.<Visit>lambdaQuery()
                .ge(Visit::getRegisteredAt, windowStart)
                .lt(Visit::getRegisteredAt, windowEnd));
        return count == null ? 0L : count;
    }

    /**
     * 当前候诊人数计数：queue_ticket WAITING 在途行数（逻辑删行自动排除）。数据库读操作。
     *
     * @return 候诊人数；无行返回 0
     */
    private long countWaiting() {
        Long count = queueTicketMapper.selectCount(
                Wrappers.<QueueTicket>lambdaQuery().eq(QueueTicket::getStatus, TicketStatus.WAITING));
        return count == null ? 0L : count;
    }

    /**
     * 按科室候诊表聚合：WAITING 行集一次取回（精确投影 queue_id/queue_time，A.4.3-14）后 JVM
     * 分组——按科室计数 + 最长等待分钟（当前时刻 − 最早 queue_time 向下取整），候诊人数降序。
     * 数据库读操作（当日候诊量有界，JVM 聚合免 GROUP BY 第二语句）。
     *
     * @return 候诊表行清单（候诊人数降序）；无候诊返回空清单
     */
    private List<DeptWaitingRow> aggregateWaitingByDept() {
        // 数据库读操作：WAITING 行集（仅取分组两列，禁 SELECT *）
        List<QueueTicket> waiting = queueTicketMapper.selectList(Wrappers.<QueueTicket>lambdaQuery()
                .select(QueueTicket::getQueueId, QueueTicket::getQueueTime)
                .eq(QueueTicket::getStatus, TicketStatus.WAITING));
        if (waiting.isEmpty()) {
            return List.of();
        }
        OffsetDateTime now = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ);
        Map<String, List<QueueTicket>> byDept = waiting.stream()
                .collect(Collectors.groupingBy(QueueTicket::getQueueId, LinkedHashMap::new, Collectors.toList()));
        return byDept.entrySet().stream()
                .map(entry -> new DeptWaitingRow(
                        entry.getKey(),
                        entry.getValue().size(),
                        // 最长等待 = 当前时刻 − 该科最早 queue_time，分钟向下取整（负值防御截 0——时钟回拨不出现负等待）
                        Math.max(
                                0,
                                entry.getValue().stream()
                                        .map(QueueTicket::getQueueTime)
                                        .filter(time -> time != null)
                                        .min(Comparator.naturalOrder())
                                        .map(earliest ->
                                                Duration.between(earliest, now).toMinutes())
                                        .orElse(0L))))
                .sorted(Comparator.comparingLong(DeptWaitingRow::waitingCount).reversed())
                .toList();
    }

    /**
     * 窗口逐日趋势聚合：XML GROUP BY 聚合行 + 缺日零填充（窗口逐日出点，stat_date 升序——前端
     * 折线图免判空的稳定契约形态）。数据库读操作（聚合查询）。
     *
     * @param window 趋势窗口，非空
     * @return 逐日趋势行（含零填充日）；恒非空
     */
    private List<DailyVisitTrendRow> aggregateTrend(TrendWindow window) {
        // 数据库读操作：窗口 GROUP BY 聚合（XML 语句）
        Map<LocalDate, DailyVisitTrendRow> byDate =
                visitStatsMapper.sumDailyVisits(window.fromDate(), window.toDate()).stream()
                        .collect(Collectors.toMap(DailyVisitTrendRow::statDate, Function.identity()));
        List<DailyVisitTrendRow> trend = new ArrayList<>();
        // 逐日游标零填充：有聚合行取真值，无行日落零点（稳定出点序列）
        for (LocalDate date = window.fromDate(); !date.isAfter(window.toDate()); date = date.plusDays(1)) {
            DailyVisitTrendRow row = byDate.get(date);
            trend.add(row != null ? row : new DailyVisitTrendRow(date, 0L, 0L));
        }
        return List.copyOf(trend);
    }
}
