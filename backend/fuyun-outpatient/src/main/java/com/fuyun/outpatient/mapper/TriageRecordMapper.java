package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.TriageRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分诊动作留痕 mapper：单表只增插入与轨迹查询经 BaseMapper 链式能力（无注解 SQL 面——只增语义
 * 零更新零删除）。必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface TriageRecordMapper extends BaseMapper<TriageRecord> {}
