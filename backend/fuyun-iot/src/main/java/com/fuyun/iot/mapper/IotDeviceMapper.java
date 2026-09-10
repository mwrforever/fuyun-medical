package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotDeviceEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备档案 mapper：状态机 apply 的条件更新与档案存在性查询（自然键 device_id）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描
 * （basePackages=com.fuyun 一次覆盖全部模块）。
 */
@Mapper
public interface IotDeviceMapper extends BaseMapper<IotDeviceEntity> {}
