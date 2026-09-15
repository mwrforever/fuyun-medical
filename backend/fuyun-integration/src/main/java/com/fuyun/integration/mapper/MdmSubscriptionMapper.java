package com.fuyun.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.integration.entity.MdmSubscription;
import org.apache.ibatis.annotations.Mapper;

/**
 * 主数据订阅台账 mapper：单表 CRUD 经 BaseMapper 内置能力（无 XML，宪法 A.4.3-15）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface MdmSubscriptionMapper extends BaseMapper<MdmSubscription> {}
