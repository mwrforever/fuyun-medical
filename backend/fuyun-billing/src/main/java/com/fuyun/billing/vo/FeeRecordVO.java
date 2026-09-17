package com.fuyun.billing.vo;

import com.fuyun.billing.entity.FeeRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 费用查询出参（controller GET /fees / POST /fees/manual / POST /fees/{id}/cancel 共用载体；
 * 组件清单为 Task 18 IT 与 Task 19 前端唯一依据，禁改名改序）：枚举出 code 字符串、
 * record 组件访问器同名；金额/id 一律 Long 包装出网（统一契约口径），经 Jackson→string。
 */
public record FeeRecordVO(
        Long id,
        String feeNo,
        Long patientId,
        String visitId,
        String visitType,
        Long chargeItemId,
        String itemNameSnapshot,
        Long unitPriceSnapshot,
        BigDecimal quantity,
        Long amount,
        String feeCategorySnapshot,
        String chargeSource,
        String sourceRef,
        String triggerPoint,
        LocalDate billingDate,
        String nhsaCodeSnapshot,
        String catalogVersionSnapshot,
        BigDecimal selfPayRatioSnapshot,
        Long limitPriceSnapshot,
        Integer priceVersion,
        Long settlementId,
        String execOccupyStatus,
        String status,
        String operator,
        String manualReason,
        OffsetDateTime chargedAt) {

    /**
     * 实体 → 出参静态工厂（controller 出网边界专用，禁实体直出；金额为关键业务字段禁 MapStruct 手写映射）。
     *
     * @param fee 费用明细实体，非空；来源：service 事务内查询结果
     * @return 出参 VO，非空；枚举列转 code 字符串，快照列原样透传
     */
    public static FeeRecordVO from(FeeRecord fee) {
        return new FeeRecordVO(
                fee.getId(),
                fee.getFeeNo(),
                fee.getPatientId(),
                fee.getVisitId(),
                fee.getVisitType() == null ? null : fee.getVisitType().getCode(),
                fee.getChargeItemId(),
                fee.getItemNameSnapshot(),
                fee.getUnitPriceSnapshot(),
                fee.getQuantity(),
                fee.getAmount(),
                fee.getFeeCategorySnapshot(),
                fee.getChargeSource() == null ? null : fee.getChargeSource().getCode(),
                fee.getSourceRef(),
                fee.getTriggerPoint() == null ? null : fee.getTriggerPoint().getCode(),
                fee.getBillingDate(),
                fee.getNhsaCodeSnapshot(),
                fee.getCatalogVersionSnapshot(),
                fee.getSelfPayRatioSnapshot(),
                fee.getLimitPriceSnapshot(),
                fee.getPriceVersion(),
                fee.getSettlementId(),
                fee.getExecOccupyStatus() == null
                        ? null
                        : fee.getExecOccupyStatus().getCode(),
                fee.getStatus() == null ? null : fee.getStatus().getCode(),
                fee.getOperator(),
                fee.getManualReason(),
                fee.getChargedAt());
    }
}
