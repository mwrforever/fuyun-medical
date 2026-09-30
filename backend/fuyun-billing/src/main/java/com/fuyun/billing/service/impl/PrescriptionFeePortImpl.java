package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.billing.api.PrescriptionFeePort;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.service.IPricingEngineService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处方联动费用作废实现（PrescriptionFeePort 唯一实现，装配归 BillingWebConfig @Bean）：
 * 定位谓词=source_ref+trigger_point=PRESCRIPTION_EFFECTIVE+status=PENDING，逐行复用引擎既有
 * cancel（PENDING→CANCELLED，行保留释放 billing_key）；REQUIRED 传播加入 M06 调用方事务。
 */
@Slf4j
public class PrescriptionFeePortImpl implements PrescriptionFeePort {

    private final FeeRecordMapper feeRecordMapper;

    private final IPricingEngineService engine;

    /**
     * 全参构造器（装配归 BillingWebConfig @Bean 显式构造）。
     *
     * @param feeRecordMapper 费用行 mapper，非空
     * @param engine          计价引擎（cancel 语义复用），非空
     */
    public PrescriptionFeePortImpl(FeeRecordMapper feeRecordMapper, IPricingEngineService engine) {
        this.feeRecordMapper = feeRecordMapper;
        this.engine = engine;
    }

    /**
     * 按来源单据号作废处方触发的在途 PENDING 费用行组（M06 处方作废联动，语义与 REST
     * {@code POST /fees/{id}/cancel} 同源，契约全量见 {@link PrescriptionFeePort}）。
     *
     * <p>执行流程：三段定位谓词（source_ref + trigger_point=PRESCRIPTION_EFFECTIVE +
     * status=PENDING）查在途行组 → 逐行复用引擎 {@code cancel}（PENDING→CANCELLED，行保留
     * 释放 billing_key，无第二套作废逻辑）→ 返回实际作废行数；REQUIRED 传播加入 M06 调用方
     * 事务（处方作废与费用作废一体成败），行级异常随调用方事务整体回滚。
     *
     * @param sourceRef 来源单据引用（=rx_no，PRESCRIPTION_EFFECTIVE 通道），非空；来源：M06 处方作废入参
     * @param reason    作废原因（审计留痕），非空白；来源：M06 处方作废理由
     * @return 实际作废费用行数（0=无在途 PENDING 费用，幂等合法）
     * @throws com.fuyun.common.exception.BizException 行状态竞态经引擎 cancel 抛 BILL-1011
     *                 （PENDING 已被并发迁移，0 行命中显式拒），随 M06 调用方事务统一回滚
     */
    @Override
    @Transactional
    public int cancelPendingBySourceRef(String sourceRef, String reason) {
        // 数据库读操作：处方通道在途 PENDING 费用行组（billing_key 唯一键前缀同源口径）
        List<FeeRecord> rows = feeRecordMapper.selectList(Wrappers.<FeeRecord>lambdaQuery()
                .eq(FeeRecord::getSourceRef, sourceRef)
                .eq(FeeRecord::getTriggerPoint, TriggerType.PRESCRIPTION_EFFECTIVE)
                .eq(FeeRecord::getStatus, FeeStatus.PENDING));
        for (FeeRecord fee : rows) {
            // 数据库写操作：逐行作废（复用 REST /fees/{id}/cancel 同一服务方法，禁第二套作废逻辑）
            engine.cancel(fee.getId(), reason);
        }
        log.info("处方联动费用作废：sourceRef={}，作废行数={}，reason={}", sourceRef, rows.size(), reason);
        return rows.size();
    }
}
