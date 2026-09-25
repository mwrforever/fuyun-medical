package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.OrderFrequency;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用药频次专业字典 mapper：单表只读链式能力（长期医嘱 freq_code 校验查询——IP-1021 拦截面；
 * 种子数据面归 V904 迁移，字典扩充走后续种子迁移禁应用层写入）。
 */
@Mapper
public interface OrderFrequencyMapper extends BaseMapper<OrderFrequency> {}
