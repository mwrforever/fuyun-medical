package com.fuyun.billing.vo;

import com.fuyun.billing.entity.InsuranceMapping;
import com.fuyun.billing.enums.InsurancePayType;
import com.fuyun.billing.enums.MapType;
import com.fuyun.billing.enums.MappingStatus;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 医保对照出参（FU-M13-01 贯标管理面）：项目级 22 项编码对照查询回显载体。
 * id/limitPrice（分）经 Long 包装出网（金额红线出参口径）；selfPayRatio 按列精度 DECIMAL(5,4) 直出。
 */
@Getter
@Setter
public class InsuranceMappingVO {

    /** 对照行 id（雪花） */
    private Long id;

    /** 收费项目 id */
    private Long chargeItemId;

    /** 对照类型（TREATMENT/DRUG/CONSUMABLE） */
    private MapType mapType;

    /** 国家医保 22 项编码 */
    private String nhsaCode;

    /** 目录版本（快照字段） */
    private String catalogVersion;

    /** 先自付比例（0-1） */
    private BigDecimal selfPayRatio;

    /** 医保限价（分，NULL=无限价） */
    private Long limitPrice;

    /** 支付属性（CLASS_A/CLASS_B/CLASS_C/SELF_EXPENSE） */
    private InsurancePayType insurancePayType;

    /** 对照状态 ACTIVE/EXPIRED */
    private MappingStatus status;

    /** 对照校验回执摘要（可空） */
    private String checkReceipt;

    /**
     * 实体 → 出参静态工厂（controller 出网边界专用，禁实体直出）。
     *
     * @param mapping 对照实体，非空；来源：service 事务内查询结果
     * @return 出参 VO，非空
     */
    public static InsuranceMappingVO from(InsuranceMapping mapping) {
        InsuranceMappingVO vo = new InsuranceMappingVO();
        vo.setId(mapping.getId());
        vo.setChargeItemId(mapping.getChargeItemId());
        vo.setMapType(mapping.getMapType());
        vo.setNhsaCode(mapping.getNhsaCode());
        vo.setCatalogVersion(mapping.getCatalogVersion());
        vo.setSelfPayRatio(mapping.getSelfPayRatio());
        vo.setLimitPrice(mapping.getLimitPrice());
        vo.setInsurancePayType(mapping.getInsurancePayType());
        vo.setStatus(mapping.getStatus());
        vo.setCheckReceipt(mapping.getCheckReceipt());
        return vo;
    }
}
