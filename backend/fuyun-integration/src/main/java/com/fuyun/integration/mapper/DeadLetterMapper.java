package com.fuyun.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.integration.entity.DeadLetter;
import org.apache.ibatis.annotations.Mapper;

/**
 * 死信台账 mapper：dead_letter 单表插入经 BaseMapper 内置能力（无 XML，宪法 A.4.3-15）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描
 * （basePackages=com.fuyun 一次覆盖全部模块）。P0 只增不查——死信列表/重放/关闭查询归
 * P1 死信管理界面交付。
 */
@Mapper
public interface DeadLetterMapper extends BaseMapper<DeadLetter> {}
