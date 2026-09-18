package com.fuyun.billing.record;

/**
 * 一日清单大类汇总聚合投影（XML 聚合 SQL 专用返回载体，{@code FeeRecordMapper.dailyListSummary}
 * 唯一消费点）：GROUP BY fee_category_snapshot + SUM 的行投影，ORDER BY 保证大类唯一顺序
 * （宪法 A.4.3-15/17）。
 *
 * @param feeCategorySnapshot 清单费用大类快照（分组键，费用行冻结列）
 * @param amount              该大类当日金额合计（分，SUM 聚合值）
 */
public record DailyListRow(String feeCategorySnapshot, Long amount) {}
