package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.OrderAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 住院医嘱审核流水 mapper（单表链式，只增 INSERT 面）：审核行落库归 OrderAuditServiceImpl
 * （系统自动审核与药师审方回执两写入点）；结论词表（PASSED/REJECTED）与 stage 词表
 * （SYSTEM/PHARMACIST）由服务层枚举/常量保证与 V905 列注释逐字同源。
 */
@Mapper
public interface OrderAuditMapper extends BaseMapper<OrderAudit> {}
