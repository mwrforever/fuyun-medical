package com.fuyun.pharmacy.service;

/**
 * 发药服务（FU-M06-04 门诊发药闭环）：Task 5 交付缴费放行与费用回执两消费入口；
 * Task 6 补齐调剂三段（pick/verify/issue）与工作台回显；Task 7 扩展退药受理两时点
 * （acceptReturn）与 refund.approved 终态收敛；Task 10 追加执行占用查询（occupancy，供 M13 位）；
 * PR-5 Task 11 放行链回切——放行/退费收口按单据精确清单承载、verify 增可选凭证核验、
 * order.cancelled 未发药作废路径实装（接口方法只增不改形）。
 */
public interface IDispenseService {

    /**
     * 缴费放行（charged 消费业务）：按结算覆盖的处方号精确清单放行——逐 rxNo 定位
     * PENDING_FEE 的门诊/急诊处方，逐单 CAS 转 PENDING_DISPENSE 并创建 CREATED 发药单与
     * 明细入队（Spec §5 流程 1；裁决 4 单据精确放行——禁 visit 维度全量扫描，避免误放
     * 未纳入本结算的处方）；空清单=该结算无药品行（纯检查/检验结算）info 跳过。
     *
     * @param rxNos 本次结算覆盖的处方号精确清单，非 null（可空清单=合法跳过面）；
     *              来源：outpatient.order.charged 载荷 rxNos（V204 id 25 冻结契约）
     */
    void releaseByRxNos(java.util.List<String> rxNos);

    /**
     * 费用回执（fee.created 消费业务）：处方通道（trigger_point=PRESCRIPTION_EFFECTIVE，
     * sourceRef=rx_no 经 billingKey 第三段守卫）驱动 APPROVED→PENDING_FEE（Spec R2-14）。
     *
     * @param billingKey 计费唯一键（patientId|sourceRef|triggerPoint|chargeItemId|billingDate），
     *                   非空；来源：billing.fee.created 载荷
     */
    void markPendingFee(String billingKey);

    /**
     * 配药（CREATED→PICKING）：FEFO 选批并条件锁定（防并发超发），追溯码逐码采集落明细行。
     *
     * @param dispenseNo 调剂单号
     * @param lines      逐行采集面（prescriptionItemId + 追溯码集，逐盒采集「无码不结」口径）
     * @throws BizException PH-1008（调剂单缺单）/ PH-1009（调剂单状态违例）/ PH-1004（处方缺单）/
     *                      PH-1005（处方状态违例）/ PH-1010（批次不足）
     */
    void pick(String dispenseNo, java.util.List<com.fuyun.pharmacy.dto.PickLine> lines);

    /**
     * 扫码核对（PICKING→PICKED）：核对药师=当前登录者，双签守卫 verifier≠picker（PH-1011）；
     * 凭证非空时增取药凭证核验（裁决 8：凭证=settlementNo，经 billing SettlementQueryPort
     * 反查其与处方归属一致性，不一致 PH-1018）——空凭证跳过核验，追溯码逐码采集维持防回流主道。
     *
     * @param dispenseNo 调剂单号
     * @param credential 取药凭证（结算单号），可空；来源：M06 药师工作站扫码/手工录入
     * @throws BizException PH-1008 / PH-1009 / PH-1011 / PH-1018（凭证与处方归属不一致）
     */
    void verify(String dispenseNo, String credential);

    /**
     * 发药签名（PICKED→ISSUED）：批次扣减+出库流水同事务，处方转 DISPENSED，
     * 发布 pharmacy.dispense.completed（批次摘要）；发药前重申双签守卫。
     *
     * @param dispenseNo 调剂单号
     * @throws BizException PH-1008 / PH-1009 / PH-1004（处方缺单）/ PH-1005（处方状态违例）/
     *                      PH-1011 / PH-1010（锁定不足违例）
     */
    void issue(String dispenseNo);

    /**
     * 退药受理（POST /dispense-returns，R2-13 两时点）：
     * <ul>
     * <li>mode=ISSUED_RETURN（发药后实物退）：追溯码与发药采集记录逐码核验（PH-1012 防回流药，
     * Spec §10）→ 批次回补+RETURN_RESTOCK 回补流水同事务（流水正数，与批次变更勾稽）→ 逐明细
     * returnedQty ≤ issuedQty 累计守卫（PH-1013）→ 发药单 ISSUED/PART_RETURNED→PART/FULL_RETURNED
     * （受理完成即置终态基点，Spec :134）→ 发布 pharmacy.dispense.returned（携退药行摘要 lines[] 非空，
     * id 29 desc 冻结——billing 占用回退/退费联动读此面）；处方终态归 refund.approved 镜像（Spec :132）。</li>
     * <li>mode=DISPENSING_CANCEL（发药中明细退场，时点②）：仅 PICKING 单可受理——释放锁定批次
     * （锁定数非数量流水，不落 stock_ledger）+ 明细退场 CANCELLED；处方保持 DISPENSING 继续剩余
     * 明细调配，不发 returned 事件。</li>
     * </ul>
     *
     * @param req 退药受理入参（单号/受理模式/逐行退药面），非空；来源：M06 药师工作站提交
     * @throws BizException PH-1008（缺单）/ PH-1009（终态 CAS 并发被抢）/
     *                      PH-1012（追溯码不一致或缺码，防回流拒）/ PH-1013（模式未知、状态违例、
     *                      缺行、超可退数、回补/释放条件更新 0 行——整事务回滚零写面）
     */
    void acceptReturn(com.fuyun.pharmacy.dto.DispenseReturnRequest req);

    /**
     * 退费终态收敛（refund.approved 消费业务，PR-5 单据化收口——注记⑦误伤面闭合）：按反查
     * 处方号清单逐 rxNo 定位处方与活动发药单，DISPENSED 态处方按发药单受理终态镜像
     * PART/FULL_RETURNED（Spec :132「billing.refund.approved 后终态」，以 M13 回执为退费权威）。
     * 幂等面：处方已退药终态重读跳过（与 order.cancelled 双通道重复到达只收敛一次）；
     * 清单处方定位不到、活动发药单缺位与受理未终态（跨队列乱序）warn 跳过不阻断消费位；
     * 未发药退费（PENDING_DISPENSE/DISPENSING）warn 跳过（终态确认归 order.cancelled 作废通道）。
     *
     * @param rxNos 退费涉及的处方号精确清单（调用方经 billing SettlementQueryPort 反查承载，
     *              非空清单；来源：sourceRefsOfSettlement(settlementId).rxRefs()）
     */
    void confirmRefundTerminalByRx(java.util.List<String> rxNos);

    /**
     * 未发药作废（order.cancelled 消费业务，PR-5 注记⑥回切）：按退费逆向处方号清单逐 rxNo
     * 将 PENDING_DISPENSE/DISPENSING 态处方 CAS 作废并同步发药单退场（Spec :132/:134——
     * 活动发药单 CANCELLED、批次锁定释放、明细退场，复用 DISPENSING_CANCEL 退场段形态）。
     * 已发药终态（DISPENSED/PART/FULL_RETURNED）info 跳过（已由 refund.approved 双通道收敛）；
     * 已 CANCELLED 幂等重投跳过；清单处方定位不到与状态域外（未缴费作废归 cancel API）warn 跳过。
     *
     * @param rxNos 退费逆向涉及的处方号精确清单，非空清单；来源：outpatient.order.cancelled
     *              载荷 rxNos（V204 id 31 冻结契约，经 billing SettlementQueryPort 反查扇出）
     * @param reason 退费原因（载荷透传，作废留痕日志锚点），可空
     */
    void voidUndispensedByRx(java.util.List<String> rxNos, String reason);

    /**
     * 执行占用查询（供 M13 退费前置校验调用位，Spec :172；billing 不切——BILL-1017 维持
     * exec_occupy_status 列口径，本 API 为 P3 切换面）。
     *
     * @param patientId 患者 id（读侧经归一缓存映射主档），非空
     * @param visitId   就诊号，可空
     * @param itemCode  收费项目 code，可空
     * @return 占用行集（处方×明细×发药单三维投影；无命中返回空集）
     */
    java.util.List<com.fuyun.pharmacy.vo.OccupancyVO> occupancy(long patientId, String visitId, String itemCode);

    /**
     * 按处方号查发药单（前端工作台回显）：排除 CANCELLED 取消态历史行（W-24——
     * uk_dispense_rx_active 允许「取消单+重建活动单」共存，一处方一张活动单）。
     *
     * @param rxNo 处方号，非空
     * @return 发药单出参；无活动单返回 null
     */
    com.fuyun.pharmacy.vo.DispenseVO getByRxNo(String rxNo);
}
