package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.TemperatureChartPage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 体温单月页 mapper：单表链式能力（ensurePage 查—无则插 + 唯一索引冲突重查兜底；
 * uk_chart_page_visit_month 部分唯一索引由 DB 兜底并发建页，无独立条件更新面）。
 */
@Mapper
public interface TemperatureChartPageMapper extends BaseMapper<TemperatureChartPage> {}
