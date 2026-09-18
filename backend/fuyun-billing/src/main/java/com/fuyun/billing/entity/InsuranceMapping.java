package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.billing.enums.InsurancePayType;
import com.fuyun.billing.enums.MapType;
import com.fuyun.billing.enums.MappingStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 医保对照实体（billing.insurance_mapping，FU-M13-01 贯标载体）：项目级 22 项国家编码登记，
 * ACTIVE 对照行唯一有效由部分唯一索引保证，贯标硬校验数据前提。线程安全：可变实体仅
 * service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("billing.insurance_mapping")
public class InsuranceMapping {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 收费项目 id */
    private Long chargeItemId;

    /** 对照类型（TREATMENT 诊疗/DRUG 药品/CONSUMABLE 耗材） */
    private MapType mapType;

    /** 国家医保 22 项编码 */
    private String nhsaCode;

    /** 目录版本（快照字段，目录动态更新可重现） */
    private String catalogVersion;

    /** 先自付比例（0-1，DECIMAL(5,4)） */
    private BigDecimal selfPayRatio;

    /** 医保限价（分，NULL=无限价） */
    private Long limitPrice;

    /** 支付属性（CLASS_A 甲/CLASS_B 乙/CLASS_C 丙/SELF_EXPENSE 自费） */
    private InsurancePayType insurancePayType;

    /** 对照状态 ACTIVE/EXPIRED */
    private MappingStatus status;

    /** 对照校验回执摘要（贯标辅助校验留痕，可空） */
    private String checkReceipt;

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
