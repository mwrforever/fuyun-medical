package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotGatewayEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 边缘网关档案 mapper（iot.iot_gateway，FU-M14-12，P2 PR-2 Task 11）：网关 CRUD 全走链式
 * wrapper（无复杂 SQL，不需要 XML——宪法 A.4.3-15 复杂 SQL 才落 mapper XML）。
 * 必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface IotGatewayMapper extends BaseMapper<IotGatewayEntity> {}
