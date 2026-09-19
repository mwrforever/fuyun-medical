package com.fuyun.pharmacy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 调剂单实体（pharmacy.dispense，V703）：发药闭环主单（charged 放行入队起点），一处方一张活动单
 * （uk_dispense_rx_active，CANCELLED 不占）。枚举字段以 String 承载 code（值域见 enums 包），
 * 双签留痕 picker/verifier/issuer 随三段与签名回写。
 */
@Getter
@Setter
@TableName("pharmacy.dispense")
public class Dispense {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 调剂单号（D+yyyyMMdd+6 位纳秒尾数，uk 唯一） */
    private String dispenseNo;

    /** 调剂单类型（DispenseType code） */
    private String dispenseType;

    /** 处方 id（引用处方主数据，红线 1 不复制明细为权威） */
    private Long prescriptionId;

    /** 处方号（检索/事件载荷锚） */
    private String rxNo;

    /** 患者主索引（M02） */
    private Long patientId;

    /** CF-3 门诊就诊号（O 型 14 位） */
    private String visitId;

    /** 库房编码（P1 演示常量 OUTP_PHARM） */
    private String storehouse;

    /** 调配药师（双签之一） */
    private String picker;

    /** 核对药师（双签之二，≠picker，PH-1011） */
    private String verifier;

    /** 发药签名操作者 */
    private String issuer;

    /** 发药时刻 */
    private OffsetDateTime issuedAt;

    /** 发药单状态（DispenseStatus code） */
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
