package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.VisitStatusLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 就诊状态迁移日志 mapper：只增表（红线 5 每迁必记）——仅插入与按 visit_id 回放查询，经 BaseMapper
 * 链式能力，无更新型注解 SQL（零更新零逻辑删）。必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig
 * 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface VisitStatusLogMapper extends BaseMapper<VisitStatusLog> {}
