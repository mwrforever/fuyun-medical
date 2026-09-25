package com.fuyun.billing.api;

/**
 * 出院费用预审只读端口（M04 住院域进程内调用，api 包唯一出口；OutpatientBillingPort 同款
 * 先例）：出院申请时点的费用结清状况快照——M04 零资金字段落地（04 Spec 红线 3），欠费判定
 * 权威在 M13，本端口只读回显（预审通过 READY / 欠费 BLOCKED 附欠费额快照，M04 侧落
 * discharge_request.arrears_amount——billing 权威数据的快照回显，请求面零金额输入）。
 * 只读面：零状态迁移、零资金动作；实现归 billing 侧（P2 PR-1 Task 13 随 V1001–V1003 落地）。
 */
public interface BillingAccountQueryPort {

    /**
     * 出院费用预审：按 CF-3 住院就诊号聚合未结清费用合计与押金余额，给出是否结清判定
     * （结清口径=未结清费用合计 ≤ 押金余额——押金覆盖内视为预审通过；实现侧聚合权威）。
     *
     * @param visitId CF-3 住院就诊号（I 型 14 位），非空；来源：M04 出院申请就诊行
     * @return 预审快照（未结清合计/押金余额/是否结清），非空；无费用行与零余额押金账户
     *         返回零值视图（视为结清——预缴押金为零且无欠费的正常出院）
     */
    DischargePrecheckView precheck(String visitId);
}
