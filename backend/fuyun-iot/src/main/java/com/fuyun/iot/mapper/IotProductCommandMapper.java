package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotProductCommandEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 产品命令安全等级 mapper：命令白名单全量替换（逻辑删旧 + 插新）与查询。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface IotProductCommandMapper extends BaseMapper<IotProductCommandEntity> {}
