package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.TemperatureChartPage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 体温单月页 mapper：单表链式能力（ensurePage 查—无则插；uk_chart_page_visit_month 部分唯一
 * 索引兜底并发建页，唯一冲突由服务层转 NS-1016 幂等拒绝——调用方重试语义），无独立条件更新面。
 */
@Mapper
public interface TemperatureChartPageMapper extends BaseMapper<TemperatureChartPage> {}
