package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.billing.api.BillingAccountQueryPort;
import com.fuyun.billing.api.DischargePrecheckView;
import com.fuyun.billing.entity.DepositAccount;
import com.fuyun.billing.mapper.DepositAccountMapper;
import com.fuyun.billing.mapper.FeeRecordMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 出院费用预审只读端口实现（BillingAccountQueryPort 唯一实现，Task 9 冻结接口的 Task 13 承载，
 * 装配归 BillingWebConfig @Import）：聚合未结清费用合计（fee_record PENDING+CONFIRMED 未结算
 * 求和）与押金余额（deposit_account 一就诊一账户，无账户为 0），结清口径=未结清合计 ≤ 押金余额
 * （接口 javadoc 冻结语义——M04 据此定预审态 READY/BLOCKED）。只读面：零状态迁移、零资金动作；
 * 费用与押金均为本模块权威数据，禁第二套聚合口径。线程安全：无状态单例。
 */
@Slf4j
public class BillingAccountQueryPortImpl implements BillingAccountQueryPort {

    private final FeeRecordMapper feeRecordMapper;

    private final DepositAccountMapper depositAccountMapper;

    /**
     * 全参构造器（装配归 BillingWebConfig @Import，backend 宪法 B.1）。
     *
     * @param feeRecordMapper      费用行 mapper，非空；未结清合计聚合语句承载
     * @param depositAccountMapper 押金账户 mapper，非空；余额读取
     */
    public BillingAccountQueryPortImpl(FeeRecordMapper feeRecordMapper, DepositAccountMapper depositAccountMapper) {
        this.feeRecordMapper = feeRecordMapper;
        this.depositAccountMapper = depositAccountMapper;
    }

    /**
     * 出院费用预审聚合（接口 javadoc 契约，Task 9 冻结三组件出参）：未结清合计（PENDING+CONFIRMED
     * 且未结算）→ 押金余额快照（无账户 0）→ 是否结清布尔（≤0 欠费口径判定）。
     *
     * @param visitId CF-3 住院就诊号，非空；来源：M04 出院申请就诊行
     * @return 预审快照（未结清合计/押金余额/是否结清），非空；无费用行与零余额押金账户返回零值
     *         视图（视为结清——预缴押金为零且无欠费的正常出院）
     */
    @Override
    public DischargePrecheckView precheck(String visitId) {
        // 数据库读操作：未结清费用合计（mapper 聚合语句，PENDING+CONFIRMED 未结算求和）
        long unsettled = feeRecordMapper.sumUnsettledAmount(visitId);
        // 数据库读操作：押金账户余额（一就诊一账户 uk_deposit_visit；无账户=0——接口冻结口径）
        DepositAccount account = depositAccountMapper.selectOne(
                Wrappers.<DepositAccount>lambdaQuery().eq(DepositAccount::getVisitId, visitId));
        long depositBalance = account == null || account.getBalance() == null ? 0L : account.getBalance();
        // 结清口径（接口 javadoc 冻结）：未结清合计 ≤ 押金余额——押金覆盖内预审通过
        boolean settled = unsettled <= depositBalance;
        log.info("出院费用预审聚合完成：visitId={}，未结清合计={}分，押金余额={}分，结清={}", visitId, unsettled, depositBalance, settled);
        return new DischargePrecheckView(unsettled, depositBalance, settled);
    }
}
