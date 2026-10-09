package com.fuyun.pharmacy.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 药事工作量统计只读快照（PharmacyStatsPort 返回载体，M06 → M19 统计接口位契约）：待配药
 * 在途单计数 + 待配药事件行集（工作台事件流轮询源），全部纯只读聚合零患者级明细（M19 红线 1
 * 分析不回写）。
 *
 * @param pendingDispenseCount 待配药在途调剂单数（CREATED 放行入队/PICKING 配药中两态合计），
 *                             非空（无在途单为 0）
 * @param pendingDispenseEvents 待配药事件行集（created_at 降序有界），非空（无在途单为空清单）
 */
public record PharmacyWorkloadStats(long pendingDispenseCount, List<PendingDispenseEvent> pendingDispenseEvents) {

    /**
     * 待配药事件行（工作台事件流 pharmacy 轮询源单行，纯计数/快照面零患者标识）。
     *
     * @param dispenseNo 调剂单号（事件流幂等判别键），非空
     * @param rxNo       处方号（事件标题素材），非空
     * @param createdAt  建单时刻（事件时点排序锚），非空
     */
    public record PendingDispenseEvent(String dispenseNo, String rxNo, OffsetDateTime createdAt) {}
}
