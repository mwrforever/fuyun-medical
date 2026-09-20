package com.fuyun.pharmacy.vo;

import com.fuyun.pharmacy.entity.Drug;
import java.math.BigDecimal;
import java.util.List;

/**
 * 药品出参（from(entity) 手写映射——关键业务字段禁 MapStruct，A.7-4；Long 经 Jackson→string）。
 * insuredSettleable 为派生标记：nhsaCode 非空=可医保结算；「未对照可院内启用但显式标记
 * 不可医保结算」（Spec :154）由 false 值承载，前端以标签渲染。
 */
public record DrugVO(
        Long id,
        String drugCode,
        String genericName,
        String tradeName,
        String dosageForm,
        String specification,
        String manufacturer,
        List<String> routeCodes,
        String unit,
        BigDecimal splitRatio,
        String nhsaCode,
        String nhsaCatalogVersion,
        String nhsaPayType,
        boolean essentialFlag,
        String antibioClass,
        String hazardLevel,
        boolean skinTestFlag,
        String narcoticClass,
        String itemCode,
        String traceCodeType,
        String indication,
        String maxDose,
        String contraindication,
        String storageCondition,
        boolean insuredSettleable,
        String status) {

    /**
     * 实体→出参静态工厂（routeCodes 逗号分隔文本还原为集）。
     *
     * @param entity 药品行，非空
     * @return 出参，非空
     */
    public static DrugVO from(Drug entity) {
        return new DrugVO(
                entity.getId(),
                entity.getDrugCode(),
                entity.getGenericName(),
                entity.getTradeName(),
                entity.getDosageForm(),
                entity.getSpecification(),
                entity.getManufacturer(),
                entity.getRouteCodes() == null || entity.getRouteCodes().isBlank()
                        ? List.of()
                        : List.of(entity.getRouteCodes().split(",")),
                entity.getUnit(),
                entity.getSplitRatio(),
                entity.getNhsaCode(),
                entity.getNhsaCatalogVersion(),
                entity.getNhsaPayType(),
                Boolean.TRUE.equals(entity.getEssentialFlag()),
                entity.getAntibioClass(),
                entity.getHazardLevel(),
                Boolean.TRUE.equals(entity.getSkinTestFlag()),
                entity.getNarcoticClass(),
                entity.getItemCode(),
                entity.getTraceCodeType(),
                entity.getIndication(),
                entity.getMaxDose(),
                entity.getContraindication(),
                entity.getStorageCondition(),
                entity.getNhsaCode() != null && !entity.getNhsaCode().isBlank(),
                entity.getStatus());
    }
}
