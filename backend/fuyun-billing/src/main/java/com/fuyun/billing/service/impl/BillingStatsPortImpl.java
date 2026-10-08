package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.billing.api.BillingStatsPort;
import com.fuyun.billing.api.BillingWorkloadStats;
import com.fuyun.billing.api.BillingWorkloadStats.PendingFeeEvent;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.mapper.FeeRecordMapper;
import java.time.LocalDate;
import java.util.List;

/**
 * 收费工作量统计端口实现（BillingStatsPort 承载，M13 → M19 统计接口位；BillingAccountQueryPortImpl
 * 同款形态）：当日收入合计/待结算笔数两格计数（mapper 注解聚合）+ 待支付事件行集（wrapper 只读
 * 投影）。纯只读，零事务零写面。
 *
 * <p>事件行集口径：当日 PENDING 行 charged_at 降序有界取回（LIMIT 下推数据库，大屏有界纪律），
 * 只映射事件四要素（feeNo/itemName/amountFen/chargedAt），零患者标识（汇总面天然脱敏，M19
 * 红线 5）。无状态单例；装配归 BillingWebConfig @Import；JaCoCo billing.service.impl 核心包
 * LINE=1.00 成员。
 */
public class BillingStatsPortImpl implements BillingStatsPort {

    /** 待支付事件行集默认上界（事件流单源截断，合并侧另有总上限） */
    static final int PENDING_EVENTS_LIMIT = 20;

    /** 费用 mapper：两格注解聚合与事件行集取数面 */
    private final FeeRecordMapper feeRecordMapper;

    /**
     * 全参构造器（装配归 BillingWebConfig @Import，backend 宪法 B.1）。
     *
     * @param feeRecordMapper 费用明细 mapper，非空；聚合与事件行集取数面
     */
    public BillingStatsPortImpl(FeeRecordMapper feeRecordMapper) {
        this.feeRecordMapper = feeRecordMapper;
    }

    /**
     * 收费工作量三段聚合（只读）：收入合计 → 待结算计数 → 待支付事件行集。
     *
     * @param date 统计计费日，非空
     * @return 工作量统计快照，非空；无业务数据返回零值/空清单视图
     */
    @Override
    public BillingWorkloadStats workloadStats(LocalDate date) {
        // 数据库读操作：单标量聚合两连查（mapper 注解 SQL，deleted=0 显式补齐）
        long todayIncomeFen = feeRecordMapper.sumDailyValidAmount(date);
        long pendingSettleCount = feeRecordMapper.countPendingUnsettled(date);
        List<PendingFeeEvent> pendingFeeEvents = loadPendingFeeEvents(date);
        return new BillingWorkloadStats(date, todayIncomeFen, pendingSettleCount, pendingFeeEvents);
    }

    /**
     * 待支付费用事件行集：当日 PENDING 行按 charged_at 降序有界取回（LIMIT 下推，last 拼接
     * 有界——NurseBoardServiceImpl 同款纪律），精确投影事件四要素（A.4.3-14 禁 SELECT *）。
     * 数据库读操作。
     *
     * @param date 统计计费日，非空
     * @return 事件行清单（charged_at 降序）；无行返回空清单
     */
    private List<PendingFeeEvent> loadPendingFeeEvents(LocalDate date) {
        // 数据库读操作：待支付行集（降序有界 + 四列投影）
        List<FeeRecord> rows = feeRecordMapper.selectList(Wrappers.<FeeRecord>lambdaQuery()
                .select(
                        FeeRecord::getFeeNo,
                        FeeRecord::getItemNameSnapshot,
                        FeeRecord::getAmount,
                        FeeRecord::getChargedAt)
                .eq(FeeRecord::getBillingDate, date)
                .eq(FeeRecord::getStatus, FeeStatus.PENDING)
                .orderByDesc(FeeRecord::getChargedAt)
                .last("LIMIT " + PENDING_EVENTS_LIMIT));
        return rows.stream()
                .map(row -> new PendingFeeEvent(
                        row.getFeeNo(), row.getItemNameSnapshot(), row.getAmount(), row.getChargedAt()))
                .toList();
    }
}
