package com.fuyun.pharmacy.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.pharmacy.api.PharmacyStatsPort;
import com.fuyun.pharmacy.api.PharmacyWorkloadStats;
import com.fuyun.pharmacy.api.PharmacyWorkloadStats.PendingDispenseEvent;
import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.enums.DispenseStatus;
import com.fuyun.pharmacy.mapper.DispenseMapper;
import java.util.List;

/**
 * 药事工作量统计端口实现（PharmacyStatsPort 承载，M06 → M19 统计接口位；BillingAccountQueryPortImpl
 * 同款形态）：待配药在途单计数 + 事件行集（wrapper 只读投影）。纯只读，零事务零写面。
 *
 * <p>在途口径：CREATED 放行入队/PICKING 配药中两态（PICKED 已配待核对起即不构成待配药待办）；
 * 事件行集 created_at 降序有界取回（LIMIT 下推数据库，大屏有界纪律），只映射事件三要素，
 * 零患者标识（M19 红线 5）。无状态单例；装配归 PharmacyWebConfig @Import；JaCoCo
 * pharmacy.service.impl 核心包 LINE=1.00 成员。
 */
public class PharmacyStatsPortImpl implements PharmacyStatsPort {

    /** 待配药事件行集默认上界（事件流单源截断，合并侧另有总上限） */
    static final int PENDING_EVENTS_LIMIT = 20;

    /** 调剂单 mapper：在途计数与事件行集取数面 */
    private final DispenseMapper dispenseMapper;

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import，backend 宪法 B.1）。
     *
     * @param dispenseMapper 调剂单 mapper，非空；在途计数与事件行集取数面
     */
    public PharmacyStatsPortImpl(DispenseMapper dispenseMapper) {
        this.dispenseMapper = dispenseMapper;
    }

    /**
     * 药事工作量两段聚合（只读）：在途计数 → 待配药事件行集。
     *
     * @return 工作量统计快照，非空；无在途单返回零值/空清单视图
     */
    @Override
    public PharmacyWorkloadStats pendingStats() {
        // 数据库读操作：在途单计数（逻辑删行由 @TableLogic 自动排除）
        Long count = dispenseMapper.selectCount(Wrappers.<Dispense>lambdaQuery()
                .in(Dispense::getStatus, DispenseStatus.CREATED.getCode(), DispenseStatus.PICKING.getCode()));
        return new PharmacyWorkloadStats(count == null ? 0L : count, loadPendingDispenseEvents());
    }

    /**
     * 待配药事件行集：在途两态行按 created_at 降序有界取回（LIMIT 下推，last 拼接有界——
     * NurseBoardServiceImpl 同款纪律），精确投影事件三要素（A.4.3-14 禁 SELECT *）。
     * 数据库读操作。
     *
     * @return 事件行清单（created_at 降序）；无行返回空清单
     */
    private List<PendingDispenseEvent> loadPendingDispenseEvents() {
        // 数据库读操作：在途行集（降序有界 + 三列投影）
        List<Dispense> rows = dispenseMapper.selectList(Wrappers.<Dispense>lambdaQuery()
                .select(Dispense::getDispenseNo, Dispense::getRxNo, Dispense::getCreatedAt)
                .in(Dispense::getStatus, DispenseStatus.CREATED.getCode(), DispenseStatus.PICKING.getCode())
                .orderByDesc(Dispense::getCreatedAt)
                .last("LIMIT " + PENDING_EVENTS_LIMIT));
        return rows.stream()
                .map(row -> new PendingDispenseEvent(row.getDispenseNo(), row.getRxNo(), row.getCreatedAt()))
                .toList();
    }
}
