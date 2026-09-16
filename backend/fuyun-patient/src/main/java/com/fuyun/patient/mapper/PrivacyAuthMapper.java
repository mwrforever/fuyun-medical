package com.fuyun.patient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.patient.entity.PrivacyAuth;
import org.apache.ibatis.annotations.Mapper;

/**
 * 患者隐私授权 mapper：单表操作经 BaseMapper/IService 链式能力（无 XML，宪法 A.4.3-15）。
 * 必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface PrivacyAuthMapper extends BaseMapper<PrivacyAuth> {}
