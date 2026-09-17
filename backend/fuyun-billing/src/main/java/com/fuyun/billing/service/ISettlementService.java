package com.fuyun.billing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.billing.dto.SettleRequest;
import com.fuyun.billing.dto.SettlementPreviewRequest;
import com.fuyun.billing.entity.Settlement;
import com.fuyun.billing.vo.SettlementPreviewVO;
import com.fuyun.billing.vo.SettlementVO;

/**
 * 结算服务（billing.settlement，FU-M13-03 收款侧）：预结算划价与正式结算收银。
 * 金额全部服务端计算与勾稽（明细合计=结算总额、支付明细合计=总额，Spec §9）；医保拆分按
 * 预结算回执落库、本地不自行计算基金拆分（红线 1，Task 15 网关回填）；已结算只读（红线 2）。
 */
public interface ISettlementService extends IService<Settlement> {

    /**
     * 预结算（划价收款依据）。
     *
     * @param req 预结算请求（patientId/visitId/payerType），组件冻结见接口块
     * @return 结算单草稿（自费 DRAFT / 医保 PRESETTLED 锁价）
     * @throws com.fuyun.common.exception.BizException BILL-1008（409 无待结算费用）/
     *                 BILL-1006（409 医保 payer 下存在未贯标费用行，随 Task 15 网关分支回填）/
     *                 BILL-1016（409 勾稽不平，随 Task 15 五拆分勾稽回填）/
     *                 BILL-1024（医保通道业务失败；网关交付前医保 payer 临时显式拒）
     */
    SettlementPreviewVO preview(SettlementPreviewRequest req);

    /**
     * 正式结算（幂等以 settleNo 终态为流水号锚点：重放同单直返不重复扣费）。
     *
     * @param req 结算请求（settleNo 非空；payments 支付明细行非空——CARD_BALANCE 行
     *            channelRef=卡账户 id 字符串，组件冻结见接口块）
     * @return 结算出参
     * @throws com.fuyun.common.exception.BizException BILL-1014（404 缺单）/ BILL-1015（409 状态不允许）/
     *                 BILL-1016（409 两层勾稽任一不平）/ BILL-1012（400 CARD_BALANCE 行缺/非法卡
     *                 账户引用或多卡混付）；PAT-1013/1014/1016（就诊卡记账失败经调用方事务回滚上抛）
     */
    SettlementVO settle(SettleRequest req);

    /**
     * 按结算编号查结算单（工作站结算回执查询 GET /settlements/{no} 消费；纯读出口，已结算
     * 只读红线 2 下不携带任何状态迁移副作用）。
     *
     * @param settleNo 结算编号，非空；来源：收费员收银后回显/前端路由参数
     * @return 结算出参，非空
     * @throws com.fuyun.common.exception.BizException BILL-1014（404 结算单不存在）
     */
    SettlementVO getByNo(String settleNo);
}
