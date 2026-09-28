package com.fuyun.ward.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.ward.entity.WardCallRoutingRuleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 呼叫路由规则 mapper：规则圈定走 BaseMapper wrapper（ward_id+call_type+deleted=0 条件，
 * uk_ward_call_routing_rule 部分唯一索引准入），时段命中解析归服务层（HHmm-HHmm 文本判定的
 * 应用层职责，SQL 不做字符串切分）。
 */
@Mapper
public interface WardCallRoutingRuleMapper extends BaseMapper<WardCallRoutingRuleEntity> {}
