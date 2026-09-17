package com.fuyun.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.record.DailyListRow;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 费用明细 mapper：单表操作经 BaseMapper 链式能力，另声明一日清单大类聚合语句
 * （复杂聚合 SQL 走 mapper+XML，宪法 A.4.3-15）。必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface FeeRecordMapper extends BaseMapper<FeeRecord> {

    /**
     * 一日清单大类汇总聚合（GROUP BY fee_category_snapshot + SUM，ORDER BY 保证大类唯一顺序
     * A.4.3-15/17）：谓词人群与明细查询（visit_id+billing_date）严格同口径——两侧一致是三层
     * 勾稽（明细合计=大类汇总合计=总额）成立的前提。XML {@code #{visitId}}/{@code #{date}}
     * 按名绑定，@Param 显式标注（对齐 CardAccountMapper.mutateBalance 形态，第 2 轮审查 P2-6）。
     *
     * @param visitId CF-3 住院就诊号，非空；来源：service 守卫后入参
     * @param date    清单计费日，非空；来源：前端日期选择
     * @return 大类聚合行列表（feeCategorySnapshot 升序唯一序）；无费用日返回空列表
     */
    List<DailyListRow> dailyListSummary(@Param("visitId") String visitId, @Param("date") LocalDate date);
}
