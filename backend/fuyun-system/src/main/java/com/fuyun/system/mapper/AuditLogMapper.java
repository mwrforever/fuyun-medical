package com.fuyun.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.system.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志 mapper：只增表仅使用 BaseMapper.insert（应用层零 UPDATE/DELETE，宪法红线）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描
 * （basePackages=com.fuyun 一次覆盖全部模块）。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {}
