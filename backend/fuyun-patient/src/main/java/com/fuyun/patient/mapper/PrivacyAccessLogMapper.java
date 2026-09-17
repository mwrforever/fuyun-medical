package com.fuyun.patient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.patient.entity.PrivacyAccessLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感查阅留痕 mapper（只增表）：insert 落痕与 selectPage 台账检索经 BaseMapper 链式能力
 * （无 XML，宪法 A.4.3-15）。必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface PrivacyAccessLogMapper extends BaseMapper<PrivacyAccessLog> {}
