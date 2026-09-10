package com.fuyun.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.system.entity.DictItemEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 字典条目 mapper：单表 CRUD 全部经 BaseMapper 内置能力（无 XML，宪法 A.4.3-13/15）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描
 * （basePackages=com.fuyun 一次覆盖全部模块）。
 */
@Mapper
public interface DictItemMapper extends BaseMapper<DictItemEntity> {}
