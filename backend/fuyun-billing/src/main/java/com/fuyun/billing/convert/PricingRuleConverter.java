package com.fuyun.billing.convert;

import com.fuyun.billing.entity.PricingRule;
import com.fuyun.billing.vo.PricingRuleVO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 计价规则域 MapStruct 转换器（backend 宪法 A.7-4；BUG-23 迁入 PricingRuleVO.from 手写直映）：
 * 计价规则实体 → 出参 VO 直映集中点——实体审计列不在出参契约内由转换器自动忽略；itemScope
 * 保持 JSON 文本原样直出（配置面仅展示，解析归计价引擎），触发型/规则状态枚举与出参同型直映
 * 零翻译，本 VO 无金额列。
 *
 * <p>componentModel 取默认（非 spring）：controller 侧经 {@link #INSTANCE} 静态获取
 * （outpatient 转换器同款范式，宪法 B.1 装配归 app 侧配置）。
 */
@Mapper
public interface PricingRuleConverter {

    /** 默认组件模型的生成实现获取入口（单测与 controller 调用同源） */
    PricingRuleConverter INSTANCE = Mappers.getMapper(PricingRuleConverter.class);

    /**
     * 计价规则实体 → 出参直映（7 字段；remark 可空直传——未填备注时为 null）。
     *
     * @param rule 规则实体，非空；来源：service 事务内规则清单查询结果
     * @return 规则出参，非空
     */
    PricingRuleVO toVO(PricingRule rule);
}
