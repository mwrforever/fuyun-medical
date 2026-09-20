package com.fuyun.pharmacy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 药品批次实体（pharmacy.drug_batch，V703）：库存权威账底座（Spec 红线 2），(库房, 药品, 批号)
 * 唯一。现存数量与配药锁定数分离表达占用；数量变更必经 DrugBatchMapper 条件更新并同步落
 * stock_ledger 流水，禁直改库存。枚举字段以 String 承载 code（值域见 enums 包）。
 */
@Getter
@Setter
@TableName("pharmacy.drug_batch")
public class DrugBatch {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 药品引用（drug 字典） */
    private Long drugId;

    /** 库房编码（P1 演示常量 OUTP_PHARM；三级库随 P3） */
    private String storehouse;

    /** 批次号 */
    private String batchNo;

    /** 生产日期（先产先出序键） */
    private LocalDate productionDate;

    /** 有效期至（近效期先出序键） */
    private LocalDate expireDate;

    /** 现存数量（基础单位，>=0） */
    private BigDecimal quantity;

    /** 配药锁定数（>=0 且 <=现存数量） */
    private BigDecimal lockedQty;

    /** 货位 */
    private String location;

    /** 供应商引用（采购域随 P3） */
    private String supplierRef;

    /** 批次状态（BatchStatus code） */
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
