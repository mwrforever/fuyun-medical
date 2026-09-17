package com.fuyun.billing.vo;

import com.fuyun.billing.entity.Settlement;

/**
 * 结算出参（POST /settlements / GET /settlements/{no}；组件清单为 Task 18 IT 与 Task 19 前端
 * 唯一依据，禁改名改序）：枚举出 code 字符串、金额/id 一律 Long 包装出网（统一契约口径）经
 * Jackson→string；settledAt 出 ISO-8601 文本（未结算为 null）。settle_type 列实体复用
 * VisitType 枚举——值域 OUT/IN/PEIS 与 Spec settle_type 一致，出院结算归 IN，V603 注释同源。
 *
 * @param id                结算单 id
 * @param settleNo          结算编号（幂等锚点）
 * @param patientId         患者主索引
 * @param visitId           CF-3 就诊号
 * @param payerType         支付类型 SELF_PAY/CITY_INS/…
 * @param settleType        结算类型 OUT/IN/PEIS
 * @param status            结算状态 DRAFT/PRESETTLED/SETTLED/REFUNDED/RED_REVERSED/CANCELLED
 * @param totalAmount       应结总额（分）
 * @param pooledAmount      统筹支付（分，医保回执，自费为 null）
 * @param acctPayAmount     个账支付（分，医保回执，自费为 null）
 * @param selfPayAmount     自付（分，医保回执，自费为 null）
 * @param selfExpenseAmount 自费（分，目录外；自费结算=全额）
 * @param preSelfPayAmount  先自付（分，医保回执，自费为 null）
 * @param settledAt         正式结算时刻（ISO-8601 文本，未结算为 null）
 */
public record SettlementVO(
        Long id,
        String settleNo,
        Long patientId,
        String visitId,
        String payerType,
        String settleType,
        String status,
        Long totalAmount,
        Long pooledAmount,
        Long acctPayAmount,
        Long selfPayAmount,
        Long selfExpenseAmount,
        Long preSelfPayAmount,
        String settledAt) {

    /**
     * 实体 → 出参静态工厂（controller 出网边界专用，禁实体直出；金额为关键业务字段禁 MapStruct 手写映射）。
     *
     * @param st 结算单实体，非空；来源：settle/getByNo 事务内查询结果
     * @return 出参 VO，非空；枚举列转 code 字符串，settledAt 转 ISO-8601 文本
     */
    public static SettlementVO from(Settlement st) {
        return new SettlementVO(
                st.getId(),
                st.getSettleNo(),
                st.getPatientId(),
                st.getVisitId(),
                st.getPayerType() == null ? null : st.getPayerType().getCode(),
                st.getSettleType() == null ? null : st.getSettleType().getCode(),
                st.getStatus() == null ? null : st.getStatus().getCode(),
                st.getTotalAmount(),
                st.getPooledAmount(),
                st.getAcctPayAmount(),
                st.getSelfPayAmount(),
                st.getSelfExpenseAmount(),
                st.getPreSelfPayAmount(),
                st.getSettledAt() == null ? null : st.getSettledAt().toString());
    }
}
