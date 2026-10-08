package com.fuyun.outpatient.mapper;

import com.fuyun.outpatient.api.OutpatientWorkloadStats.DailyVisitTrendRow;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 门诊工作量统计 mapper（纯聚合型，不继承 BaseMapper——A.4.3-20 聚合/报表型接口形态）：
 * 14 日趋势 GROUP BY 聚合语句承载（复杂聚合 SQL 走 mapper+XML，宪法 A.4.3-15；FeeRecordMapper
 * .dailyListSummary 同款先例）。必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface VisitStatsMapper {

    /**
     * 窗口内逐日门诊人次聚合（GROUP BY 北京钟面自然日 + COUNT/FILTER，ORDER BY 保证日期唯一
     * 顺序 A.4.3-15/17）：原生 SQL 不继承 @TableLogic，deleted=0 显式补齐（FeeRecordMapper.xml
     * 同款口径）；急诊双序列经 COUNT(*) FILTER 单次扫描同时产出，免二次查询。
     *
     * @param fromDate 窗口起始日（含，北京钟面自然日），非空；来源：TrendWindow 守卫后入参
     * @param toDate   窗口结束日（含），非空；来源：同上
     * @return 逐日趋势行（stat_date 升序唯一序）；窗口内无挂号行返回空列表（零填充由 service 承担）
     */
    List<DailyVisitTrendRow> sumDailyVisits(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);
}
