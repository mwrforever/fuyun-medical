package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotProductEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 产品镜像 mapper：上架落行、同步状态更新与镜像查询（自然键 product_id）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描
 * （basePackages=com.fuyun 一次覆盖全部模块）。
 */
@Mapper
public interface IotProductMapper extends BaseMapper<IotProductEntity> {}
