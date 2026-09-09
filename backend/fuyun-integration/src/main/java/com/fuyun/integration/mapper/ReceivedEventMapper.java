package com.fuyun.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.integration.entity.ReceivedEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消费幂等台账 mapper：received_event 单表插入经 BaseMapper 内置能力（无 XML，宪法 A.4.3-15）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描
 * （basePackages=com.fuyun 一次覆盖全部模块）。P0 只增不查——查询归 M20 §9 归档清理
 * 策略 P1+ 交付。
 */
@Mapper
public interface ReceivedEventMapper extends BaseMapper<ReceivedEvent> {}
