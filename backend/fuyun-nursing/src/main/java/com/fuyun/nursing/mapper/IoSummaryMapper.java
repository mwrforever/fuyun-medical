package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.IoSummary;
import org.apache.ibatis.annotations.Mapper;

/**
 * 出入量小结 mapper：单表链式能力（小结写入主链为 insert；幂等由 uk_io_summary_period
 * 部分唯一索引兜底——并发同周期冲突由服务层转 NS-1016 幂等拒绝，PG 同事务重查不可达
 * 不做冲突后回查）。shift_key 生成列不映射（GENERATED ALWAYS 禁写入）。
 */
@Mapper
public interface IoSummaryMapper extends BaseMapper<IoSummary> {}
