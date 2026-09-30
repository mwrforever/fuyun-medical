package com.fuyun.billing.vo;

import com.fuyun.billing.enums.InsurancePayType;
import com.fuyun.billing.enums.MapType;
import com.fuyun.billing.enums.MappingStatus;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 医保对照出参（FU-M13-01 贯标管理面）：项目级 22 项编码对照查询回显载体。
 * id/limitPrice（分）经 Long 包装出网（金额红线出参口径）；selfPayRatio 按列精度 DECIMAL(5,4) 直出。
 * 实体→出参直映归 {@link com.fuyun.billing.convert.InsuranceMappingConverter}（BUG-23 迁入，
 * 限价/比例原样透传零换算，禁实体直出）。
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
}
