package com.fuyun.billing.api;

/**
 * 结算单反查端口（M03 门诊域进程内调用，api 包唯一出口；OutpatientBillingPort 同款先例）：
 * M03 收费编排「单据精确」原则的反查唯一载体（裁决 5/6/8）——settlement.completed 消费按结算单
 * 反查来源单据精确清单驱动放行（禁 visit 全量扫描，避免误放未纳入本结算的单据）、refund.approved
 * 消费按同一清单驱动逆向回滚、Task 11 放行链凭 verify 凭证核验消费。只读面：零状态迁移、零资金
 * 动作（资金无涉红线裁决 7，资金权威归 M13 既有链）。
 */
public interface SettlementQueryPort {

    /**
     * 按结算单反查来源单据引用组：fee_record 按 settlement_id 查询按 trigger_point 分组——
     * ORDER_CONFIRMED 组（source_ref=申请单号）与 PRESCRIPTION_EFFECTIVE 组（source_ref=处方号），
     * 各组去重升序；其他计费点（MANUAL 挂号费等）不进投影。
     *
     * @param settlementId 结算单 id，非空非零；来源：billing.settlement.completed /
     *                     billing.refund.approved 事件载荷（V605 id 19/20）
     * @return 来源单据引用组（两组均非 null；结算单无单据费用行时为空列表）
     */
    SettlementSourceRefs sourceRefsOfSettlement(long settlementId);

    /**
     * 放行凭证核验（settlement(settle_no) × fee_record(source_ref) 反查）：该结算单下存在指定来源
     * 单据的 SETTLED 费用行即 true——Task 11 放行链 verify 的消费凭证输入侧，无命中即凭证无效。
     *
     * @param settleNo  结算编号（uk_settle_no），非空；来源：M03 放行请求凭证
     * @param sourceRef 来源单据引用（申请单号/处方号），非空
     * @return true=存在 SETTLED 费用行（凭证有效）；false=结算单缺失或无命中（未结算/无该单据行）
     */
    boolean settledUnder(String settleNo, String sourceRef);
}
