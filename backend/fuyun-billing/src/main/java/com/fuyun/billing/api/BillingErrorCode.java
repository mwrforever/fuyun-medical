package com.fuyun.billing.api;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.ErrorCode;

/**
 * M13 收费域错误码枚举（BILL-xxxx，A.3-4；前缀查证：M13 Spec 未定义，依惯例取 BILL，
 * 全仓 grep SYS-/INT-/PAT- 无冲突——2026-09-17 核验）。业务异常统一
 * {@code BizException(BillingErrorCode.X, HttpStatus, message)} 经全局渲染。
 */
public enum BillingErrorCode implements ErrorCode {

    /** 收费项目不存在（404；item_code/id 无命中） */
    CHARGE_ITEM_NOT_FOUND("BILL-1001"),
    /** 项目编码已存在（409；uk_charge_item_code 命中） */
    CHARGE_ITEM_CODE_EXISTS("BILL-1002"),
    /** 项目状态不允许该操作（409；INACTIVE 参与计价） */
    CHARGE_ITEM_STATE_NOT_ALLOWED("BILL-1003"),
    /** 价格版本冲突（409；生效区间重叠或版本号重复，uk 兜底前应用层先拒） */
    PRICE_RANGE_CONFLICT("BILL-1004"),
    /** 价格记录不存在（404） */
    PRICE_NOT_FOUND("BILL-1005"),
    /** 医保对照缺失或失效（409；贯标硬校验——参与医保结算的项目须有 ACTIVE 对照，M13 红线/FU-01） */
    INSURANCE_MAPPING_MISSING("BILL-1006"),
    /** 计价规则不存在（404） */
    PRICING_RULE_NOT_FOUND("BILL-1007"),
    /** 计价失败（409；无生效价格版本等定价不可得场景） */
    PRICING_UNAVAILABLE("BILL-1008"),
    /** 重复计费拦截（409；billing_key 唯一键命中——已收费用回显口径） */
    DUPLICATE_CHARGING("BILL-1009"),
    /** 费用记录不存在（404） */
    FEE_NOT_FOUND("BILL-1010"),
    /** 费用状态不允许该操作（409；已结算作废、非 PENDING/CONFIRMED 确认等状态机违例） */
    FEE_STATE_NOT_ALLOWED("BILL-1011"),
    /** 手工计费缺审计要素（400；操作者/理由必填，M13 红线 3）；兼支付明细行卡引用缺失/非法/多卡不一致
     *  （A.3-4 码义扩展，Task 12 settle 写入侧与 Task 13 execute 读回侧 parseCardAccountId 双边消费——
     *  第 2 轮审查 P2-4，不新增码位） */
    MANUAL_CHARGE_CONTEXT_MISSING("BILL-1012"),
    /** 就诊号格式非法（400；CF-3 定长 14 位校验失败，复用 patient VisitIdValidator 语义） */
    VISIT_ID_MALFORMED("BILL-1013"),
    /** 结算单不存在（404） */
    SETTLEMENT_NOT_FOUND("BILL-1014"),
    /** 结算状态不允许该操作（409；状态机违例：非 PRESETTLED 正式结算、已 SETTLED 重结等） */
    SETTLEMENT_STATE_NOT_ALLOWED("BILL-1015"),
    /** 金额勾稽不平（409；明细合计≠总额/支付合计≠总额，Spec §9 三层校验） */
    AMOUNT_MISMATCH("BILL-1016"),
    /** 退费明细占用中（409；已发药/已执行未先逆向业务，执行占用硬前置） */
    REFUND_BLOCKED_BY_EXEC_OCCUPY("BILL-1017"),
    /** 退费申请不存在（404） */
    REFUND_NOT_FOUND("BILL-1018"),
    /** 退费状态不允许该操作（409） */
    REFUND_STATE_NOT_ALLOWED("BILL-1019"),
    /** 退费双人守卫（403；审批人=申请人，或二级审批人=一级审批人——同一账号不得连批两级，等保分权；
     *  语义扩展与 BILL-1012/1023 先例同款，不新增码位） */
    REFUND_SELF_APPROVAL_FORBIDDEN("BILL-1020"),
    /** 退费金额超可退余额（409；含历史已退聚合） */
    REFUND_AMOUNT_EXCEEDED("BILL-1021"),
    /** 押金账户不存在（404） */
    DEPOSIT_ACCOUNT_NOT_FOUND("BILL-1022"),
    /** 押金流水状态不允许退回（409；非 ACTIVE 流水拒退）；兼押金账户终态拒缴存（原路语义泛化：
     *  资金态操作前置校验，Task 14 depositOnSettledAccountBlockedAsBill1023 消费——第 2 轮审查 P2-4，不新增码位） */
    DEPOSIT_TXN_STATE_NOT_ALLOWED("BILL-1023"),
    /** 医保通道调用失败（502；模拟/真实通道业务失败码非 0000，应急口径见 Spec §3.2） */
    INSURANCE_CALL_FAILED("BILL-1024"),
    /** 医保调用日志不存在（404） */
    INSURANCE_CALL_NOT_FOUND("BILL-1025"),
    /** 医保补偿状态不允许重试（409；非 TIMEOUT/FAILED 行拒重试） */
    INSURANCE_COMPENSATE_STATE_NOT_ALLOWED("BILL-1026"),
    /** 计价规则项目集合 JSON 非法（400；item_scope 解析失败=配置错误显式暴露，禁静默） */
    PRICING_RULE_SCOPE_INVALID("BILL-1027"),
    /** 价格状态不允许该操作（409；publish 非 DRAFT 行/重复发布） */
    PRICE_STATE_NOT_ALLOWED("BILL-1028"),
    /** 退费卡侧原付守卫（409；W-16——卡行金额≤0 或退款额>卡侧原付合计，复审 F6 加固） */
    REFUND_CARD_CHANNEL_INVALID("BILL-1029"),
    /** 退费结算单状态不允许（409；W-18——仅 SETTLED 单值可退（SETTLED 唯一可退基点），封堵 DRAFT/预结算绕过主链） */
    REFUND_SETTLEMENT_STATE_NOT_ALLOWED("BILL-1030"),
    /** 退费费用行归属不符（409；W-18——行 settlementId 与申请结算单不一致） */
    REFUND_FEE_NOT_IN_SETTLEMENT("BILL-1031"),
    /** 挂账审批单不存在（404；approval_no 无命中，M13 住院联动 P2 PR-1 Task 13） */
    ARREARS_APPROVAL_NOT_FOUND("BILL-1032"),
    /** 挂账审批状态不允许该操作（409；非 PENDING_APPROVAL 态决出/并发被抢，P2 PR-1 Task 13） */
    ARREARS_APPROVAL_STATE_NOT_ALLOWED("BILL-1033");

    /** 码值（如 BILL-1001），A.2-7 code↔enum 双向映射之 code 侧 */
    private final String code;

    BillingErrorCode(String code) {
        this.code = code;
    }

    /**
     * 取错误码字符串。
     *
     * @return 码值，非空；经全局渲染输出至 ProblemDetail.properties.errorCode
     */
    @JsonValue
    @Override
    public String getCode() {
        return code;
    }
}
