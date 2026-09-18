package com.fuyun.billing.gateway;

import com.fuyun.billing.dto.InsurancePreSettleCommand;
import com.fuyun.billing.dto.InsurancePreSettleResult;
import java.util.List;

/**
 * 医保出站网关契约（FU-M13-05 基线接口位；命名对齐 patient {@code IdentityMediaGateway} 先例——
 * gateway 无 I 前缀）。交易码对齐基线版词表（V604 列注释）：2001 门诊登记/2101 费用上传/
 * 2102 预结算/2104 撤销 + 电子凭证核验接口位。
 *
 * <p><b>已裁决 3 口径（2026-09-17）</b>：交付基线接口位 + 模拟适应器，演示走模拟通道；真实医保
 * 联调环境用户侧准备后补演，届时新增真实实现替换（Bean 注册点 BillingWebConfig#insuranceGateway，
 * P5 按配置条件化二选一），业务侧零改。
 *
 * <p><b>调用口径（2026-09-17 审查裁决，Global Constraints 事务红线同源）</b>：模拟适应器为进程内
 * 纯计算零 IO，preview/settle 事务内直调合规（不触 A.4.2-7 事务内禁外部调用/MQ 发送）；P5 真实
 * 通道改「事务外两段式：先落 insurance_call_log(INIT/SENT)→调网关→回填 SUCCESS/FAILED/TIMEOUT」，
 * 悬挂补偿随 P5。业务级留痕归 IInsuranceCallLogService（两级日志分工，本契约不含留痕职责）。
 */
public interface InsuranceGateway {

    /**
     * 医保预结算（2102）：取回基金拆分回执（本地不自行计算基金拆分，红线 1）。
     *
     * @param cmd 预结算命令（lines=费用行快照列投影），非空
     * @return 拆分回执（八组件，勾稽恒等式按构造成立），非空
     */
    InsurancePreSettleResult preSettle(InsurancePreSettleCommand cmd);

    /**
     * 医保门诊登记（2001 接口位）。
     *
     * @param visitId   CF-3 就诊号，非空
     * @param patientId 患者主索引，非空
     * @return 中心登记流水号，非空
     */
    String register(String visitId, long patientId);

    /**
     * 医保费用上传（2101 接口位）。
     *
     * @param visitId CF-3 就诊号，非空
     * @param feeIds  费用行 id 列表（非空）
     * @return 上传行数（中心受理口径）
     */
    int feeUpload(String visitId, List<Long> feeIds);

    /**
     * 医保撤销（2104 接口位；撤销端点挂 WRITE 审计）。
     *
     * @param centerSerialNo 原中心流水号（预结算/结算回执），非空
     * @param idempotencyKey 撤销幂等键，非空
     * @return 撤销中心流水号，非空
     */
    String reverse(String centerSerialNo, String idempotencyKey);

    /**
     * 医保电子凭证核验（接口位，2026-09-17 审查裁决补位）：真实凭证扫码核验随 P5 真实通道，
     * PR-3 仅契约+模拟形态。ecToken 空/空白显式拒（禁空凭证静默放行）。
     *
     * @param ecToken 医保电子凭证令牌（扫码输出原文），非空白
     * @return 核验流水号，非空
     * @throws com.fuyun.common.exception.BizException BILL-1024（502 凭证令牌空/空白）
     */
    String authenticate(String ecToken);
}
