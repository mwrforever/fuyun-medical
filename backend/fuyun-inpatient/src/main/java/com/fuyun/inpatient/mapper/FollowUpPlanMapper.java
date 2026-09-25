package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.FollowUpPlan;
import org.apache.ibatis.annotations.Mapper;

/**
 * 随访计划 mapper（V907 follow_up_plan）：单表链式能力即可承载——生成（离院确认同事务
 * INSERT）、到期扫描（idx_follow_up_plan_due status+plan_date）与完成/取消值面更新均走
 * Wrappers 条件更新（无并发争抢语义：随访行无状态机互斥，双写幂等）；暂无需注解 SQL 面。
 */
@Mapper
public interface FollowUpPlanMapper extends BaseMapper<FollowUpPlan> {}
