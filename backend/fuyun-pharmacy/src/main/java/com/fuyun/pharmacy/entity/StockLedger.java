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
 * 库存流水实体（pharmacy.stock_ledger，V703）：只增表（仅 INSERT 通道，禁 UPDATE/DELETE），
 * 批次账变更必有对应流水、结存可重建（Spec 红线 2）。action 值域见 LedgerAction（varchar+
 * 应用层枚举，动作值集扩展零迁移阻力）；deleted 列与 V703 一致保留（表层无 UPDATE 通道，
 * 逻辑删不参与业务语义）。
 */
@Getter
@Setter
@TableName("pharmacy.stock_ledger")
public class StockLedger {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 库房编码 */
    private String storehouse;

    /** 药品引用（drug 字典） */
    private Long drugId;

    /** 批次 id（drug_batch 引用） */
    private Long batchId;

    /** 流水动作（LedgerAction code） */
    private String action;

    /** 数量（出库负/回补正，基础单位） */
    private BigDecimal quantity;

    /** 关联单据（dispense_no） */
    private String refDoc;

    /** 经手人 */
    private String operator;

    /** 发生时刻 */
    private OffsetDateTime occurredAt;

    /** 创建时刻 */
    private OffsetDateTime createdAt;

    /** 创建者 */
    private String createdBy;

    /** 逻辑删标记（与 V703 列一致；只增表无 UPDATE 通道） */
    @TableLogic
    private Integer deleted;
}
