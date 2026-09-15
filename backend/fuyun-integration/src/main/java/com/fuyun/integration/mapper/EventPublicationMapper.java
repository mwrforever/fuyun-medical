package com.fuyun.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.integration.entity.EventPublication;
import org.apache.ibatis.annotations.Mapper;

/**
 * 投递注册表只读 mapper：event_publication 单表查询经 BaseMapper 内置能力（无 XML，宪法 A.4.3-15）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 * 写入归框架（Modulith 注册表 + EventOpsJob），本 mapper 禁承担任何写调用（调用方审查项）。
 */
@Mapper
public interface EventPublicationMapper extends BaseMapper<EventPublication> {}
