package com.fuyun.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.billing.entity.InsuranceMapping;
import org.apache.ibatis.annotations.Mapper;

/** 医保对照 mapper：单表操作经 BaseMapper 链式能力（无 XML，宪法 A.4.3-15）。必须标注 @Mapper 供 app 侧扫描。 */
@Mapper
public interface InsuranceMappingMapper extends BaseMapper<InsuranceMapping> {}
