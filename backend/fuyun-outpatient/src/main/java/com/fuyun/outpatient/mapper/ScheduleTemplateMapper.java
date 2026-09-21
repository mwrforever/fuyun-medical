package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.ScheduleTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 排班模板 mapper：单表操作（登记/更新/分页清单/放号取 ACTIVE 模板集）全量经 BaseMapper 链式
 * 能力（lambdaQuery/selectPage），无注解 SQL 面——模板域无并发 CAS 语句。必须标注 {@code @Mapper}：
 * app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface ScheduleTemplateMapper extends BaseMapper<ScheduleTemplate> {}
