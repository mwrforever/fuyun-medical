package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.ExecutionCheckLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 扫码核对流水 mapper（V1106 execution_check_log）：单表链式能力（只增表——插入经
 * BaseMapper.insert，无更新/删除面；清单查询按 execution_no+occurred_at 升序经
 * lambdaQuery 组装）。必须标注 @Mapper 供 app 侧扫描。
 */
@Mapper
public interface ExecutionCheckLogMapper extends BaseMapper<ExecutionCheckLog> {}
