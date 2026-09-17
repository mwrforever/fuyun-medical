package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.dto.PricingRuleUpsertRequest;
import com.fuyun.billing.entity.PricingRule;
import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.mapper.PricingRuleMapper;
import com.fuyun.billing.service.IPricingRuleService;
import com.fuyun.common.exception.BizException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计价规则实现（billing.pricing_rule，FU-M13-02 规则配置面）。
 *
 * <p>item_scope 存项目集合 JSON 文本，落库前 readTree 试解析守卫——配置错误显式暴露（BILL-1027），
 * 禁静默落库后由计价期炸雷。ruleCode 业务唯一由部分唯一索引兜底，应用层 upsert 先行拒改写语义。
 * 线程安全：无状态 singleton（ObjectMapper 为 JDK 线程安全配置载体）；写操作 @Transactional 收口。
 */
@Slf4j
public class PricingRuleServiceImpl extends ServiceImpl<PricingRuleMapper, PricingRule> implements IPricingRuleService {

    private final ObjectMapper objectMapper;

    /** 全参构造器（装配归 BillingWebConfig @Import）。 */
    public PricingRuleServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 按触发型取生效规则（计价引擎分流消费入口）。
     *
     * @param trigger 触发型，非空
     * @return ACTIVE 规则（同触发型多条取第一条）；未配置返回 null（引擎按无规则分支处理）
     */
    @Override
    @Transactional(readOnly = true)
    public PricingRule activeByTrigger(TriggerType trigger) {
        return lambdaQuery()
                .eq(PricingRule::getTriggerType, trigger)
                .eq(PricingRule::getStatus, ItemStatus.ACTIVE)
                .last("LIMIT 1")
                .one();
    }

    /**
     * 规则登记（upsert）：ruleCode 无命中插新行，有命中改原行。
     *
     * @param req 规则登记请求，非空；来源：医保办规则配置表单
     * @return 落库规则行 id（改写为原行 id，新插为回填雪花 id）
     * @throws BizException BILL-1027（400 item_scope 非法 JSON）
     */
    @Override
    @Transactional
    public long upsert(PricingRuleUpsertRequest req) {
        // 配置守卫先于任何数据库操作：非法 JSON 一律落库前拒绝
        parseScopeGuard(req.itemScope());
        // 数据库读操作：按 ruleCode 查存量（uk_pricing_rule_code 前置）
        PricingRule existing =
                lambdaQuery().eq(PricingRule::getRuleCode, req.ruleCode()).one();
        if (existing == null) {
            PricingRule row = new PricingRule();
            applyRequest(row, req);
            save(row);
            log.info("计价规则新登记：ruleCode={}，triggerType={}，ruleId={}", req.ruleCode(), req.triggerType(), row.getId());
            return row.getId();
        }
        // 数据库写操作：原行整体改写（保留 id，规则版本演进经 updated_at 留痕）
        applyRequest(existing, req);
        updateById(existing);
        log.info("计价规则改写：ruleCode={}，triggerType={}，ruleId={}", req.ruleCode(), req.triggerType(), existing.getId());
        return existing.getId();
    }

    /** 全量规则清单（配置面列表页，id 倒序新规则在前）。 */
    @Override
    @Transactional(readOnly = true)
    public List<PricingRule> listAll() {
        return lambdaQuery().orderByDesc(PricingRule::getId).list();
    }

    /**
     * item_scope JSON 试解析守卫：readTree 失败即配置错误，落库前拒绝（BILL-1027）。
     *
     * @param itemScope 待校验 JSON 文本，非空（@NotBlank 由 controller 层保证）
     * @throws BizException BILL-1027（400）JSON 语法非法时触发
     */
    private void parseScopeGuard(String itemScope) {
        try {
            objectMapper.readTree(itemScope);
        } catch (JsonProcessingException e) {
            throw new BizException(
                    BillingErrorCode.PRICING_RULE_SCOPE_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "item_scope 非法 JSON：" + e.getOriginalMessage());
        }
    }

    /** 请求字段 → 实体列映射（插入/改写两分支共用，禁散落复制漂移）。 */
    private void applyRequest(PricingRule row, PricingRuleUpsertRequest req) {
        row.setRuleCode(req.ruleCode());
        row.setRuleName(req.ruleName());
        row.setTriggerType(req.triggerType());
        row.setItemScope(req.itemScope());
        row.setStatus(req.status());
        row.setRemark(req.remark());
    }
}
