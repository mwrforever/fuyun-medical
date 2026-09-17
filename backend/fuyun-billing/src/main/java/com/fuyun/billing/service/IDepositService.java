package com.fuyun.billing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.billing.dto.DepositRequest;
import com.fuyun.billing.entity.DepositAccount;
import com.fuyun.billing.vo.DepositAccountVO;

/**
 * 押金服务（billing.deposit_account/deposit_txn，FU-M13-04 住院预交金）：多渠道缴存与账户查询。
 * 门诊预交金按 2025-03 国家政策取消不设账户（Spec §12 澄清①）；余额唯一写点为
 * DepositAccountMapper.mutateBalance 原子记账（并发缴存/抵扣串行化）；欠费阈值判定 NORMAL⇄ARREARS，
 * 变更经 billing.deposit.changed 事件（AFTER_COMMIT）广播。
 */
public interface IDepositService extends IService<DepositAccount> {

    /**
     * 住院预交金缴存（多渠道：窗口/自助机/扫码/线上，线上经 M18 复用）。
     *
     * <p>执行流程：就诊号/操作者/支付方式三守卫 → 一就诊一账户定位（缺户即开户，终态拒缴）→
     * mutateBalance 原子记账回读 → 欠费阈值判定（有效余额=回读余额−已确认未结算费用，低于阈值
     * ARREARS、回升 NORMAL）→ 流水落库 → 发 billing.deposit.changed。
     *
     * @param req 缴存请求（patientId/visitId/amountFen/paymentMethod 必填，组件冻结见 DTO 块），非空
     * @return 新缴存流水 id（POST /deposits 出网回执锚点）
     * @throws com.fuyun.common.exception.BizException BILL-1013（400 就诊号非住院形态）/
     *                 BILL-1012（400 缺登录操作者上下文；400 支付方式混入结算域值）/
     *                 BILL-1023（409 账户终态拒缴存；409 并发窗口内账户被终态化回读落空）
     */
    long deposit(DepositRequest req);

    /**
     * 按就诊号查押金账户（GET /deposits?visitId= 消费；纯读出口）。
     *
     * @param visitId CF-3 住院就诊号，非空；来源：M04 工作站/患者端路由参数
     * @return 账户出参，非空
     * @throws com.fuyun.common.exception.BizException BILL-1013（400 就诊号非住院形态）/
     *                 BILL-1022（404 该就诊号无押金账户）
     */
    DepositAccountVO getByVisit(String visitId);
}
