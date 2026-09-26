package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警规则 mapper：规则 CRUD 与引擎评估装载的读面（写面 CAS 集中在 IotAlarmMapper——规则行
 * 无并发 CAS 需求，软删经 @TableLogic deleteById）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描
 * （basePackages=com.fuyun 一次覆盖全部模块）。
 */
@Mapper
public interface IotAlarmRuleMapper extends BaseMapper<IotAlarmRuleEntity> {}
