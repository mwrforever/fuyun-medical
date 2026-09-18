package com.fuyun.billing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.billing.dto.RefundApplyRequest;
import com.fuyun.billing.entity.RefundRequest;
import com.fuyun.billing.enums.RefundStatus;
import com.fuyun.common.web.PageResult;

/**
 * 退费服务（billing.refund，FU-M13-03 退侧）：分级审批与执行原路退回。
 * 五维分级 P1 收敛为「执行占用硬前置 + 当日/跨日/已结算分级 + 免审阈值」（阈值经
 * fuyun.billing.refund 配置注入）；分级三级落地（Spec §6）：免审直退（落库即 APPROVED）、
 * 一级审批（收费组长）、二级审批（大额超 singleApprovalFen 或医保已结算 → 财务/医保办）；
 * 双人守卫=审批人≠申请人且二级批人≠一级批人（等保三级分权，BILL-1020 语义扩展）；退费负向表达
 * 收敛为 refund_fee_link 负向台账，禁 fee_record 负向行（裁决⑫）。
 */
public interface IRefundService extends IService<RefundRequest> {

    /**
     * 退费申请（免审阈值内当日未占用直退 APPROVED，其余按分级进 PENDING_APPROVAL 待一审）。
     *
     * @param req 退费申请请求（settlementId/lines/reason，金额服务端按明细算），非空
     * @return 新退费申请 id
     * @throws com.fuyun.common.exception.BizException BILL-1012（400 缺登录操作者上下文）/
     *                 BILL-1014（404 缺原结算单）/ BILL-1010（404 费用行缺行脏数据）/
     *                 BILL-1017（409 执行占用硬前置）/ BILL-1021（409 超可退余额）
     */
    long apply(RefundApplyRequest req);

    /**
     * 退费审批（分级两段式：一级批 L2 单升 PENDING_SECOND_APPROVAL、L1 单即终批 APPROVED；
     * 二级批终批落 APPROVED 并发 billing.refund.approved 事件）。
     *
     * @param id 退费申请 id；来源：审批列表选行
     * @throws com.fuyun.common.exception.BizException BILL-1018（404 缺单）/
     *                 BILL-1019（409 非待审态——PENDING_APPROVAL/PENDING_SECOND_APPROVAL 之外）/
     *                 BILL-1020（403 审批人=申请人，或二级审批人=一级审批人）
     */
    void approve(long id);

    /**
     * 退费驳回（PENDING_APPROVAL/PENDING_SECOND_APPROVAL → REJECTED 终态，理由必填留痕）。
     *
     * @param id     退费申请 id；来源：审批列表选行
     * @param reason 驳回理由，非空白；来源：审批人录入
     * @throws com.fuyun.common.exception.BizException BILL-1018（404 缺单）/
     *                 BILL-1019（409 非待审态）
     */
    void reject(long id, String reason);

    /**
     * 退费执行（APPROVED → EXECUTED，原路退回——资金动作与状态迁移同事务）。
     *
     * @param id 退费申请 id；来源：审批通过后执行入口
     * @throws com.fuyun.common.exception.BizException BILL-1018（404 缺单）/
     *                 BILL-1019（409 非 APPROVED）/ BILL-1012（400 payment_details 卡引用
     *                 缺失/非法——读回侧对称守卫，禁裸 parseLong 抛 500 出契约外）；
     *                 PAT-1013/1014（卡账户记账失败经调用方事务回滚上抛）
     */
    void execute(long id);

    /**
     * 退费申请分页查询（工作站审批列表 GET /refunds 消费；status 可空=全部、id 升序稳定行序）。
     *
     * @param status 退费状态过滤，可空（null=全部状态）；来源：前端筛选条件
     * @param page   页码（0 基），非空
     * @param size   单页条数，非空
     * @return 退费申请分页出参（实体行，出网边界由 controller 转 VO），非空
     */
    PageResult<RefundRequest> page(RefundStatus status, int page, int size);
}
