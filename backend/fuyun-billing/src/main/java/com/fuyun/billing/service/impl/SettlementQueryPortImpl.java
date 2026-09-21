package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.billing.api.SettlementQueryPort;
import com.fuyun.billing.api.SettlementSourceRefs;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.entity.Settlement;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.mapper.SettlementMapper;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 结算单反查端口实现（SettlementQueryPort 唯一实现，装配归 BillingWebConfig @Import）：
 * sourceRefsOfSettlement 单表精确投影后内存按计费点分组去重升序（fee_record 为本模块主表，
 * wrapper 链式承载，A.4.3-13/14；settlement(settle_no) × fee_record(source_ref) 的凭证核验语义
 * 以「settleNo 定位结算单 → 结算单 id × sourceRef × SETTLED 存在性」两步单表查询承载，等价
 * JOIN 且免去 XML 连表）。只读面跟随调用方事务（一致性读）；禁在本实现外新写第二套反查逻辑。
 * 线程安全：无状态单例。
 */
@Slf4j
public class SettlementQueryPortImpl implements SettlementQueryPort {

    private final FeeRecordMapper feeRecordMapper;

    private final SettlementMapper settlementMapper;

    /**
     * 全参构造器（装配归 BillingWebConfig @Import，backend 宪法 B.1）。
     *
     * @param feeRecordMapper  费用行 mapper，非空；结算单维度费用行投影与 SETTLED 存在性计数
     * @param settlementMapper 结算单 mapper，非空；结算编号定位结算单
     */
    public SettlementQueryPortImpl(FeeRecordMapper feeRecordMapper, SettlementMapper settlementMapper) {
        this.feeRecordMapper = feeRecordMapper;
        this.settlementMapper = settlementMapper;
    }

    /**
     * 按结算单反查来源单据引用组（接口 javadoc 契约）：仅取单据两计费点行，内存分组去重升序
     * （重投反查结果逐字一致，M03 消费幂等断言锚）。
     *
     * @param settlementId 结算单 id，非空非零
     * @return 来源单据引用组，非空
     */
    @Override
    public SettlementSourceRefs sourceRefsOfSettlement(long settlementId) {
        // 数据库读操作：结算单维度费用行精确投影（仅两列；trigger 限定申请单/处方两单据计费点）
        List<FeeRecord> rows = feeRecordMapper.selectList(Wrappers.<FeeRecord>lambdaQuery()
                .select(FeeRecord::getSourceRef, FeeRecord::getTriggerPoint)
                .eq(FeeRecord::getSettlementId, settlementId)
                .in(FeeRecord::getTriggerPoint, TriggerType.ORDER_CONFIRMED, TriggerType.PRESCRIPTION_EFFECTIVE)
                .orderByAsc(FeeRecord::getId));
        List<String> orderRefs = refsOf(rows, TriggerType.ORDER_CONFIRMED);
        List<String> rxRefs = refsOf(rows, TriggerType.PRESCRIPTION_EFFECTIVE);
        log.info("结算单来源单据反查完成：settlementId={}，orderRefs={}，rxRefs={}", settlementId, orderRefs, rxRefs);
        return new SettlementSourceRefs(settlementId, orderRefs, rxRefs);
    }

    /**
     * 放行凭证核验（接口 javadoc 契约）：结算编号定位结算单（缺失即凭证无效短路 false），再按
     * 结算单 id × sourceRef 计数 SETTLED 费用行（&gt;0 即有效）。
     *
     * @param settleNo  结算编号，非空
     * @param sourceRef 来源单据引用，非空
     * @return true=存在 SETTLED 费用行；false=结算单缺失或无命中
     */
    @Override
    public boolean settledUnder(String settleNo, String sourceRef) {
        // 数据库读操作：结算编号定位结算单（uk_settle_no 唯一；缺失即凭证无效）
        Settlement settlement = settlementMapper.selectOne(
                Wrappers.<Settlement>lambdaQuery().select(Settlement::getId).eq(Settlement::getSettleNo, settleNo));
        if (settlement == null) {
            log.info("放行凭证核验无命中（结算单缺失）：settleNo={}，sourceRef={}", settleNo, sourceRef);
            return false;
        }
        // 数据库读操作：SETTLED 费用行存在性计数（settlement(settle_no) JOIN fee_record(source_ref)
        // 语义的两步单表承载；仅 SETTLED 态有效——已退费/作废行不构成有效凭证）
        Long settledCount = feeRecordMapper.selectCount(Wrappers.<FeeRecord>lambdaQuery()
                .eq(FeeRecord::getSettlementId, settlement.getId())
                .eq(FeeRecord::getSourceRef, sourceRef)
                .eq(FeeRecord::getStatus, FeeStatus.SETTLED));
        boolean settled = settledCount != null && settledCount > 0;
        log.info("放行凭证核验完成：settleNo={}，sourceRef={}，settled={}", settleNo, sourceRef, settled);
        return settled;
    }

    /**
     * 按计费点分组提取来源引用（去重升序，A.4.3-17 唯一顺序）。
     *
     * @param rows    已投影费用行，非空
     * @param trigger 目标计费点，非空
     * @return 去重升序引用清单；无命中返回空列表
     */
    private static List<String> refsOf(List<FeeRecord> rows, TriggerType trigger) {
        return rows.stream()
                .filter(row -> row.getTriggerPoint() == trigger)
                .map(FeeRecord::getSourceRef)
                .distinct()
                .sorted()
                .toList();
    }
}
