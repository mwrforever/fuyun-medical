package com.fuyun.billing.dto;

/**
 * 医保预结算回执（InsuranceGateway.preSettle 出参；八组件为 Task 15 冻结签名，禁改名改序）：
 * 五拆分勾稽恒等式 total == pooled + acctPay + selfPay + selfExpense + preSelfPay 按模拟构造成立，
 * preview 侧二次勾稽（BILL-1016）消费。网关内部载体不经 HTTP 直出（出网结算面为 SettlementPreviewVO，
 * 金额出网 Long 包装口径归 VO 层）。
 *
 * @param totalAmount       应结总额（分，与 cmd 费用行求和一致回显）
 * @param pooledAmount      统筹支付（分）
 * @param acctPayAmount     个账支付（分）
 * @param selfPayAmount     自付（分，目录内余额三分之尾差归此）
 * @param selfExpenseAmount 自费（分，目录外行全额）
 * @param preSelfPayAmount  先自付（分，行金额×先自付比例 HALF_UP 整分）
 * @param centerSerialNo    中心流水号（模拟="SIM-"+幂等键，重放同值；真实通道 P5=医保中心回执流水）
 * @param catalogVersion    目录版本（模拟=适应器常量目录版本戳；真实通道 P5 以回执回显为准）
 */
public record InsurancePreSettleResult(
        long totalAmount,
        long pooledAmount,
        long acctPayAmount,
        long selfPayAmount,
        long selfExpenseAmount,
        long preSelfPayAmount,
        String centerSerialNo,
        String catalogVersion) {}
