package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.billing.api.OutpatientBillingPort;
import com.fuyun.billing.api.VisitFeeView;
import com.fuyun.billing.api.VisitRefundCommand;
import com.fuyun.billing.dto.RefundApplyRequest;
import com.fuyun.billing.dto.RefundLine;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.service.IPricingEngineService;
import com.fuyun.billing.service.IRefundService;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 门诊域收费对接端口实现（OutpatientBillingPort 唯一实现，装配归 BillingWebConfig @Import）：
 * 三方法纯转调既有服务面（apply→IRefundService、cancel→IPricingEngineService、查询→FeeRecordMapper），
 * 禁第二套退费/作废逻辑（资金动作唯一权威=M13 既有链）。REQUIRED 传播加入 M03 调用方事务
 * （同事务原子：退号申请与 M13 退费一体成败）。线程安全：无状态单例。
 */
@Slf4j
public class OutpatientBillingPortImpl implements OutpatientBillingPort {

    private final IRefundService refundService;

    private final IPricingEngineService pricingEngineService;

    private final FeeRecordMapper feeRecordMapper;

    /**
     * 全参构造器（装配归 BillingWebConfig @Import，backend 宪法 B.1）。
     *
     * @param refundService         退费服务，非空；apply 统一免审档通道
     * @param pricingEngineService  计价引擎，非空；cancel 既有作废语义复用
     * @param feeRecordMapper       费用行 mapper，非空；visit_id 维度定位查询
     */
    public OutpatientBillingPortImpl(
            IRefundService refundService, IPricingEngineService pricingEngineService, FeeRecordMapper feeRecordMapper) {
        this.refundService = refundService;
        this.pricingEngineService = pricingEngineService;
        this.feeRecordMapper = feeRecordMapper;
    }

    /**
     * 按 visit_id 全状态查询费用行组（id 升序，A.4.3-17 唯一顺序）：M03 勾选可退行/定位作废行的
     * 唯一读取面，全状态出线由调用方按 status 词表自行过滤（端口不做业务裁剪）。
     *
     * @param visitId CF-3 门诊就诊号，非空
     * @return 费用行视图列表（id 升序）；无费用返回空列表
     */
    @Override
    public List<VisitFeeView> feesByVisit(String visitId) {
        // 数据库读操作：visit 维度全状态查询（精确投影经实体读回后映射，禁 SELECT * 出模块）
        List<FeeRecord> rows = feeRecordMapper.selectList(Wrappers.<FeeRecord>lambdaQuery()
                .eq(FeeRecord::getVisitId, visitId)
                .orderByAsc(FeeRecord::getId));
        return rows.stream()
                .map(fee -> new VisitFeeView(
                        fee.getId(),
                        fee.getStatus().getCode(),
                        fee.getSourceRef(),
                        fee.getTriggerPoint().getCode(),
                        fee.getAmount(),
                        fee.getSettlementId()))
                .toList();
    }

    /**
     * 退费申请转调（统一免审档通道）：Line.quantity DECIMAL string → BigDecimal 逐条转换后进
     * IRefundService.apply（金额服务端按明细算，红线 1 退侧同源）；退费申请 id 透传返回供 M03
     * 日志留痕与回执对账。
     *
     * @param cmd 退费命令，非空
     * @return 新退费申请 id
     */
    @Override
    public long applyRefund(VisitRefundCommand cmd) {
        List<RefundLine> lines = cmd.lines().stream()
                .map(line -> new RefundLine(line.feeId(), new BigDecimal(line.quantity())))
                .toList();
        long refundId = refundService.apply(new RefundApplyRequest(cmd.settlementId(), lines, cmd.reason()));
        log.info(
                "门诊端口退费申请转调：settlementId={}，行数={}，refundId={}，reason={}",
                cmd.settlementId(),
                lines.size(),
                refundId,
                cmd.reason());
        return refundId;
    }

    /**
     * 单费用行作废转调（引擎既有 PENDING/CONFIRMED→CANCELLED 语义复用，禁新写状态迁移）：
     * 异常原样上抛（转调不吞），M03 侧按 BILL 错误码契约处置。
     *
     * @param feeId  费用行 id，非空
     * @param reason 作废理由，非空白
     */
    @Override
    public void cancelPendingFee(long feeId, String reason) {
        pricingEngineService.cancel(feeId, reason);
    }
}
