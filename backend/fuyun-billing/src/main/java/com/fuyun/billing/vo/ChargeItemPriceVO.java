package com.fuyun.billing.vo;

import com.fuyun.billing.entity.ChargeItemPrice;
import com.fuyun.billing.enums.PriceSource;
import com.fuyun.billing.enums.PriceStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 价格版本出参（FU-M13-01 调价管理面）：项目价格版本链查询回显载体。
 * id/chargeItemId/price 一律 Long 包装出网（金额红线出参口径，JacksonLongToStringConfig 统一转字符串）。
 */
@Getter
@Setter
public class ChargeItemPriceVO {

    /** 价格版本行 id（雪花） */
    private Long id;

    /** 收费项目 id */
    private Long chargeItemId;

    /** 单价（分，≥0） */
    private Long price;

    /** 版本号（项目内递增，快照回溯锚点） */
    private Integer version;

    /** 生效起（发布即生效或定时时刻） */
    private OffsetDateTime effectiveFrom;

    /** 生效止（null=当前有效版本） */
    private OffsetDateTime effectiveTo;

    /** 价格来源（OFFICIAL_DOC 物价批文/AGREEMENT 协议价） */
    private PriceSource priceSource;

    /** 批文/协议文号（可空） */
    private String approvalNo;

    /** 版本状态 DRAFT/PUBLISHED/EXPIRED */
    private PriceStatus status;

    /**
     * 实体 → 出参静态工厂（controller 出网边界专用，禁实体直出）。
     *
     * @param price 价格版本实体，非空；来源：service 事务内查询结果
     * @return 出参 VO，非空
     */
    public static ChargeItemPriceVO from(ChargeItemPrice price) {
        ChargeItemPriceVO vo = new ChargeItemPriceVO();
        vo.setId(price.getId());
        vo.setChargeItemId(price.getChargeItemId());
        vo.setPrice(price.getPrice());
        vo.setVersion(price.getVersion());
        vo.setEffectiveFrom(price.getEffectiveFrom());
        vo.setEffectiveTo(price.getEffectiveTo());
        vo.setPriceSource(price.getPriceSource());
        vo.setApprovalNo(price.getApprovalNo());
        vo.setStatus(price.getStatus());
        return vo;
    }
}
