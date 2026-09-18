package com.fuyun.billing.vo;

import com.fuyun.billing.entity.Settlement;

/**
 * 预结算出参（POST /settlements/preview；组件清单为 Task 18 IT 与 Task 19 前端唯一依据，
 * 禁改名改序）：枚举出 code 字符串、金额/id 一律 Long 包装出网（统一契约口径）经 Jackson→string；
 * 医保拆分五列仅医保 payer 预结算回填（自费为 null）。
 *
 * @param settleNo          结算编号（后续正式结算请求锚点）
 * @param patientId         患者主索引
 * @param visitId           CF-3 就诊号
 * @param payerType         支付类型 SELF_PAY/CITY_INS/…
 * @param status            结算状态 DRAFT（自费）/PRESETTLED（医保锁价）
 * @param totalAmount       应结总额（分）
 * @param pooledAmount      统筹支付（分，医保回执，自费为 null）
 * @param acctPayAmount     个账支付（分，医保回执，自费为 null）
 * @param selfPayAmount     自付（分，医保回执，自费为 null）
 * @param selfExpenseAmount 自费（分，目录外；自费结算=全额）
 * @param preSelfPayAmount  先自付（分，医保回执，自费为 null）
 */
public record SettlementPreviewVO(
        String settleNo,
        Long patientId,
        String visitId,
        String payerType,
        String status,
        Long totalAmount,
        Long pooledAmount,
        Long acctPayAmount,
        Long selfPayAmount,
        Long selfExpenseAmount,
        Long preSelfPayAmount) {

    /**
     * 实体 → 出参静态工厂（controller 出网边界专用，禁实体直出；金额为关键业务字段禁 MapStruct 手写映射）。
     *
     * @param st 结算单实体，非空；来源：preview 事务内装配结果
     * @return 出参 VO，非空；枚举列转 code 字符串，金额列原样透传（分）
     */
    public static SettlementPreviewVO from(Settlement st) {
        return new SettlementPreviewVO(
                st.getSettleNo(),
                st.getPatientId(),
                st.getVisitId(),
                st.getPayerType() == null ? null : st.getPayerType().getCode(),
                st.getStatus() == null ? null : st.getStatus().getCode(),
                st.getTotalAmount(),
                st.getPooledAmount(),
                st.getAcctPayAmount(),
                st.getSelfPayAmount(),
                st.getSelfExpenseAmount(),
                st.getPreSelfPayAmount());
    }
}
