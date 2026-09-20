package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.Drug;
import org.apache.ibatis.annotations.Mapper;

/**
 * 药品字典 mapper：单表操作经 BaseMapper 链式能力（检索过滤由 service 层 lambdaQuery 组装）。
 * 必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface DrugMapper extends BaseMapper<Drug> {}
