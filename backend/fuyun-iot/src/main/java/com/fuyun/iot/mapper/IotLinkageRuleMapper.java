package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotLinkageRuleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 联动规则 mapper：规则 CRUD 通道（软删由 @TableLogic 承载，无状态机 CAS 面）。必须标注
 * {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface IotLinkageRuleMapper extends BaseMapper<IotLinkageRuleEntity> {}
