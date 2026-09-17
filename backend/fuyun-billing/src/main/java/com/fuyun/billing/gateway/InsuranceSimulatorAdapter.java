package com.fuyun.billing.gateway;

import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.dto.InsurancePreSettleCommand;
import com.fuyun.billing.dto.InsurancePreSettleResult;
import com.fuyun.common.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/**
 * 医保网关模拟适应器（FU-M13-05，已裁决 3 口径）：交付基线接口位 + 模拟实现，演示走模拟通道；
 * 真实医保联调环境用户侧准备后补演，届时新增真实 {@link InsuranceGateway} 实现替换
 * （Bean 注册点 BillingWebConfig#insuranceGateway，P5 按配置条件化二选一），业务侧零改。
 *
 * <p>零 IO 红线：进程内纯计算与确定性取值（无 mapper/无出站调用），preview/settle 事务内直调
 * 合规（不触 A.4.2-7 事务内禁外部调用/MQ 发送）；业务级留痕归 IInsuranceCallLogService（两级
 * 日志分工：本类仅系统级 info 日志留痕）。线程安全：无状态 singleton，拆分计算无共享可变态。
 */
@Slf4j
public class InsuranceSimulatorAdapter implements InsuranceGateway {

    /** 模拟拆分比例（拍板建议⑥：目录内余额 60% 统筹 / 20% 个账 / 20% 自付，HALF_UP 整分）。 */
    private static final BigDecimal POOLED_RATE = new BigDecimal("0.6");

    private static final BigDecimal ACCT_RATE = new BigDecimal("0.2");

    /** 模拟目录版本戳（单一模拟目录；P5 真实通道以回执回显，见 Produces 注记）。 */
    private static final String SIM_CATALOG_VERSION = "SIM-2026Q3";

    /**
     * 医保预结算模拟（确定性基金拆分，零 IO）：逐行拆分后聚合回显，勾稽恒等式
     * total == pooled + acct + selfPay + selfExpense + preSelfPay 按构造成立（Task 12 preview 二次勾稽必过）。
     *
     * @param cmd 预结算命令（lines=费用行快照列投影），非空
     * @return 拆分结果（centerSerialNo="SIM-"+幂等键；catalogVersion=模拟目录版本戳），非空
     */
    @Override
    public InsurancePreSettleResult preSettle(InsurancePreSettleCommand cmd) {
        long total = 0L;
        long pooled = 0L;
        long acct = 0L;
        long selfPay = 0L;
        long selfExpense = 0L;
        long preSelf = 0L;
        for (InsurancePreSettleCommand.Line line : cmd.lines()) {
            total += line.amount();
            if (line.nhsaCode() == null) {
                // 目录外行（计费时无有效对照）：全额进自费，不参与基金拆分
                // （贯标硬校验已前置于 Task 12 preview BILL-1006，本分支服务预览前计算与真实通道形态）
                selfExpense += line.amount();
                continue;
            }
            // 先自付 = 行金额 × 先行自付比例（HALF_UP 整分；比例缺省 0=甲类全额进目录）
            BigDecimal ratio = line.selfPayRatio() == null ? BigDecimal.ZERO : line.selfPayRatio();
            long linePreSelf = BigDecimal.valueOf(line.amount())
                    .multiply(ratio)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            long base = line.amount() - linePreSelf;
            // 目录内余额三分：统筹 60%、个账 20%（各 HALF_UP），自付取余额减两拆——尾差归自付保勾稽恒等
            long linePooled = BigDecimal.valueOf(base)
                    .multiply(POOLED_RATE)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            long lineAcct = BigDecimal.valueOf(base)
                    .multiply(ACCT_RATE)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            long lineSelf = base - linePooled - lineAcct;
            preSelf += linePreSelf;
            pooled += linePooled;
            acct += lineAcct;
            selfPay += lineSelf;
        }
        // result 八组件逐名回显（冻结签名口径）：流水号由幂等键确定性派生，重放同值
        return new InsurancePreSettleResult(
                total, pooled, acct, selfPay, selfExpense, preSelf, "SIM-" + cmd.idempotencyKey(), SIM_CATALOG_VERSION);
    }

    /**
     * 门诊登记模拟（2001 接口位）：visitId 确定性派生中心流水号，即时成功（零 IO 无悬挂态）。
     *
     * @param visitId   CF-3 就诊号，非空
     * @param patientId 患者主索引，非空
     * @return 中心登记流水号（"SIM-REG-"+就诊号），非空
     */
    @Override
    public String register(String visitId, long patientId) {
        String centerSerialNo = "SIM-REG-" + visitId;
        log.info("医保门诊登记模拟成功（2001）：visit={}，patient={}，centerSerialNo={}", visitId, patientId, centerSerialNo);
        return centerSerialNo;
    }

    /**
     * 费用上传模拟（2101 接口位）：即时受理，回显上传行数（零 IO 无回执等待）。
     *
     * @param visitId CF-3 就诊号，非空
     * @param feeIds  费用行 id 列表，非空
     * @return 上传行数（=提交行数，模拟中心全量受理）
     */
    @Override
    public int feeUpload(String visitId, List<Long> feeIds) {
        log.info("医保费用上传模拟成功（2101）：visit={}，feeIds={}，上传行数={}", visitId, feeIds.size(), feeIds.size());
        return feeIds.size();
    }

    /**
     * 撤销模拟（2104 接口位）：按撤销幂等键确定性派生撤销流水号，即时成功。
     *
     * @param centerSerialNo 原中心流水号，非空
     * @param idempotencyKey 撤销幂等键，非空
     * @return 撤销中心流水号（"SIM-REV-"+幂等键），非空
     */
    @Override
    public String reverse(String centerSerialNo, String idempotencyKey) {
        String reverseSerialNo = "SIM-REV-" + idempotencyKey;
        log.info("医保撤销模拟成功（2104）：原中心流水号={}，撤销流水号={}", centerSerialNo, reverseSerialNo);
        return reverseSerialNo;
    }

    /**
     * 电子凭证核验模拟（接口位，2026-09-17 裁决补位）：非空令牌确定性派生核验流水号；
     * 空/空白显式拒——禁空凭证静默放行（实名核验入口，空令牌一律不可通过）。
     * 核验成功日志不带流水号（2026-09-18 审查修复：流水号由令牌原文派生可反推，敏感信息禁入系统日志）。
     *
     * @param ecToken 医保电子凭证令牌，非空白（扫码输出原文，禁留痕明文）
     * @return 核验流水号（"SIM-AUTH-"+令牌原文），非空
     * @throws BizException BILL-1024（502 凭证令牌空/空白；文案不带任何流水号，禁敏感派生值外泄）
     */
    @Override
    public String authenticate(String ecToken) {
        if (ecToken == null || ecToken.isBlank()) {
            // 风控守卫：空凭证显式拒（502 通道失败口径与 BILL-1024 码义同源），禁静默放行
            log.warn("医保电子凭证核验拒绝：凭证令牌为空/空白，禁空凭证静默放行");
            throw new BizException(BillingErrorCode.INSURANCE_CALL_FAILED, HttpStatus.BAD_GATEWAY, "医保电子凭证核验失败：凭证令牌为空");
        }
        String authSerialNo = "SIM-AUTH-" + ecToken;
        // 敏感信息红线（全局 AGENTS.md §二 禁 token 入日志）：流水号由令牌原文派生、可反向还原因牌，
        //   禁入系统日志——成功仅记录事实，流水号经返回值（核验出参 authSerialNo）定向出网
        log.info("医保电子凭证核验模拟成功：核验流水号已生成（令牌派生，禁日志留痕）");
        return authSerialNo;
    }
}
