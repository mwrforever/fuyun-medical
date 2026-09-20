package com.fuyun.pharmacy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 药品字典实体（pharmacy.drug，V700）：药品业务主数据权威源；国家医保编码对照在本模块维护。
 * 枚举字段以 String 承载 code（值域见 enums 包，写入侧由服务层校验），与 billing 实体同型。
 */
@Getter
@Setter
@TableName("pharmacy.drug")
public class Drug {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 院内码（业务唯一） */
    private String drugCode;

    /** 通用名 */
    private String genericName;

    /** 商品名 */
    private String tradeName;

    /** 拼音码 */
    private String pinyinCode;

    /** 剂型 */
    private String dosageForm;

    /** 规格 */
    private String specification;

    /** 生产厂家 */
    private String manufacturer;

    /** 给药途径集（M01 dict code 逗号分隔） */
    private String routeCodes;

    /** 单位 */
    private String unit;

    /** 拆零换算 */
    private BigDecimal splitRatio;

    /** 国家医保药品编码（NULL=未对照） */
    private String nhsaCode;

    /** 医保目录版本 */
    private String nhsaCatalogVersion;

    /** 支付属性（NhsaPayType code） */
    private String nhsaPayType;

    /** 基药标识 */
    private Boolean essentialFlag;

    /** 抗菌药分级（AntibacterialClass code） */
    private String antibioClass;

    /** 高警示等级（HazardLevel code） */
    private String hazardLevel;

    /** 皮试标识（true=需皮试） */
    private Boolean skinTestFlag;

    /** 毒麻类别（NarcoticClass code） */
    private String narcoticClass;

    /** 说明书·适应证 */
    private String indication;

    /** 说明书·最大剂量 */
    private String maxDose;

    /** 说明书·禁忌 */
    private String contraindication;

    /** 说明书·贮存条件 */
    private String storageCondition;

    /** 关联 M13 收费项目（NULL=不可计费） */
    private String itemCode;

    /** 追溯码类型 */
    private String traceCodeType;

    /** 状态（DrugStatus code） */
    private String status;

    /** 创建时刻 */
    private OffsetDateTime createdAt;

    /** 更新时刻 */
    private OffsetDateTime updatedAt;

    /** 创建者 */
    private String createdBy;

    /** 更新者 */
    private String updatedBy;

    /** 逻辑删标记 */
    @TableLogic
    private Integer deleted;
}
