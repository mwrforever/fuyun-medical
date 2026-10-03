package com.fuyun.pharmacy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 调剂单实体（pharmacy.dispense，V703 + V1110 住院扩列）：发药闭环主单（门诊 charged 放行入队
 * 起点 / 住院摆药 issue 出库落行），一处方一张活动单（uk_dispense_rx_active 谓词限 OUTPATIENT，
 * 住院行不受约束）。枚举字段以 String 承载 code（值域见 enums 包），双签留痕 picker/verifier/
 * issuer 随三段与签名回写；住院三列（wardId/m04OrderNo/dispensePlanNo）门诊行 NULL，住院行
 * 必填由应用层（DispensePlanServiceImpl.issue 落库面）保证。
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

    /** 调剂单类型（DispenseType code：OUTPATIENT 门诊/INPATIENT_DOSE 住院单剂量/INPATIENT_PIVA 静配） */
    private String dispenseType;

    /**
     * 处方 id（引用处方主数据，红线 1 不复制明细为权威）；住院摆药行无处方承载 0 占位——
     * uk_dispense_rx_active 谓词限 OUTPATIENT 不占，住院行不受一处方一活动单约束（V1110 扩列声明）
     */
    private Long prescriptionId;

    /**
     * 处方号（检索/事件载荷锚）；住院摆药行承载摆药计划号可读锚（rx_no NOT NULL 列双语义承载，
     * V1110 住院扩列口径——事件载荷 rxNo 仍出 null，列值仅供检索）
     */
    private String rxNo;

    /** 患者主索引（M02） */
    private Long patientId;

    /** 就诊号双语义承载：门诊行 O 型 14 位 / 住院行 I 型 14 位（V1110 列注释双语义声明） */
    private String visitId;

    /** 库房编码（P1 演示常量 OUTP_PHARM——住院链共用单一药房库，分库随 P3） */
    private String storehouse;

    /** 调配药师（双签之一） */
    private String picker;

    /** 核对药师（双签之二，≠picker，PH-1011） */
    private String verifier;

    /** 发药签名操作者 */
    private String issuer;

    /** 发药时刻 */
    private OffsetDateTime issuedAt;

    /** 目标病区编码（住院摆药行归属病区；门诊行 NULL；V1110 住院扩列） */
    private String wardId;

    /** 住院医嘱号（住院行回链 M04 医嘱；门诊行 NULL；V1110 住院扩列） */
    private String m04OrderNo;

    /** 摆药计划号（住院行回链 dispense_plan.plan_no；门诊行 NULL；V1110 住院扩列） */
    private String dispensePlanNo;

    /** 发药单状态（DispenseStatus code；住院链 PICKED→CHECKED→DELIVERED，行经 issue 诞生即 CHECKED） */
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
