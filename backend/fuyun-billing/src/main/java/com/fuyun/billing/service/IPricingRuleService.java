package com.fuyun.billing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.billing.dto.PricingRuleUpsertRequest;
import com.fuyun.billing.entity.PricingRule;
import com.fuyun.billing.enums.TriggerType;
import java.util.List;

/**
 * 计价规则服务（billing.pricing_rule，FU-M13-02 规则配置面）：触发型生效规则查询
 * （引擎分流消费）与 ruleCode 业务唯一的规则登记。
 */
public interface IPricingRuleService extends IService<PricingRule> {

    /**
     * 按触发型取生效规则（计价引擎分流消费入口，Task 11）。
     *
     * @param trigger 触发型，非空
     * @return ACTIVE 规则（同触发型多条取第一条）；未配置返回 null（引擎按无规则分支处理）
     */
    PricingRule activeByTrigger(TriggerType trigger);

    /**
     * 规则登记（upsert）：ruleCode 无命中插新行，有命中改原行；item_scope 落库前 JSON 守卫。
     *
     * @param req 规则登记请求，非空；来源：医保办规则配置表单
     * @return 落库规则行 id（改写为原行 id，新插为回填雪花 id）
     * @throws com.fuyun.common.exception.BizException BILL-1027（400 item_scope 非法 JSON）
     */
    long upsert(PricingRuleUpsertRequest req);

    /**
     * 全量规则清单（配置面列表页）。
     *
     * @return 规则清单，可为空清单
     */
    List<PricingRule> listAll();
}
