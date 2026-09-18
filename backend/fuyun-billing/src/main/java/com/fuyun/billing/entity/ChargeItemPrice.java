package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.billing.enums.PriceSource;
import com.fuyun.billing.enums.PriceStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 价格版本实体（billing.charge_item_price，方案 3.4）：DRAFT→PUBLISHED→EXPIRED 版本链，
 * 「当前唯一有效版本」由部分唯一索引硬保证；历史版本为计费快照回溯锚点。
 * 线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("billing.charge_item_price")
public class ChargeItemPrice {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 收费项目 id（应用层关联） */
    private Long chargeItemId;

    /** 单价（分，≥0，金额红线 BIGINT 分值制） */
    private Long price;

    /** 版本号（项目内递增，快照回溯锚点） */
    private Integer version;

    /** 生效起（发布即生效或定时时刻） */
    private OffsetDateTime effectiveFrom;

    /** 生效止（NULL=当前有效版本） */
    private OffsetDateTime effectiveTo;

    /** 价格来源（OFFICIAL_DOC 物价批文/AGREEMENT 协议价） */
    private PriceSource priceSource;

    /** 批文/协议文号（可空） */
    private String approvalNo;

    /** 版本状态 DRAFT/PUBLISHED/EXPIRED */
    private PriceStatus status;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护 */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入 */
    private String createdBy;

    /** 审计列 */
    private String updatedBy;

    /** 逻辑删标记（@TableLogic 全局配置） */
    @TableLogic
    private Short deleted;
}
