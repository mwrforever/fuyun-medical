package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.NursingWardConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病区护理配置 mapper：单表读面（按病区编码取配置行）；P1 无写端点（配置经 V801 种子与 P2 运维面维护）。
 */
@Mapper
public interface NursingWardConfigMapper extends BaseMapper<NursingWardConfig> {}
