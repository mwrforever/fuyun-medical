package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.ApptCreditRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 爽约信用记录 mapper：单表操作（信用行插入/窗口计数/限约区间回读）经 BaseMapper 链式能力，
 * 无跨表注解 SQL（限约拦截谓词由 service 层 lambdaQuery 承载）。必须标注 {@code @Mapper}：
 * app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface ApptCreditRecordMapper extends BaseMapper<ApptCreditRecord> {}
