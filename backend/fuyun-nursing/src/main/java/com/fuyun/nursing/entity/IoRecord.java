package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 出入量明细账实体（nursing.io_record，V804）：出入量明细权威记录（Spec :111）。P1 写入方
 * 仅手工（工作站）与 PDA；输液执行自动带入（INFUSION_AUTO，P2 执行域）/输血自动带入
 * （TRANSFUSION_AUTO，M12）/ICU 自动汇总与手工（ICU_AUTO/ICU_MANUAL，P4）为枚举预留——
 * source_ref 列随自动带入方 P2 填、shift_code 班次归属 P1 手工行不承载（空=未归属班次）。
 * 数量落库 BigDecimal NUMERIC(10,2)（D-18：出入参 string 承载，VO 侧 toPlainString）。
 */
@Getter
@Setter
@TableName("nursing.io_record")
public class IoRecord {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 住院就诊号（I 型 14 位；在区校验经 IWardMetaService） */
    private String visitId;

    /** 患者主索引（在区行归一后服务端装配，不信客户端） */
    private Long patientId;

    /** 病区编码（在区行归一后服务端装配） */
    private String wardId;

    /** 发生时间（业务时间一律服务器时间，GC25） */
    private OffsetDateTime occurAt;

    /** 出入量类型（IoType code：INTAKE 入量 / OUTPUT 出量） */
    private String ioType;

    /** 项目 code（入量：IV_FLUID/ORAL/NASOGASTRIC/BLOOD；出量：URINE/STOOL/VOMIT/DRAINAGE/PUNCTURE） */
    private String itemCode;

    /** 项目名称（冗余展示名，服务端按 IoItemCode 词表落库，字典未建时前端直显） */
    private String itemName;

    /** 数量（入量 ml、出量 ml；重量类 g；NUMERIC(10,2)） */
    private BigDecimal quantity;

    /** 单位（缺省 ml） */
    private String unit;

    /** 数据源（IoSource code：P1 仅 MANUAL/PDA 有写入方） */
    private String source;

    /** 来源单据引用（输液执行单号等，P2 写入方，P1 恒空） */
    private String sourceRef;

    /** 班次 code（取病区班次定义；空=未归属班次，P1 手工行恒空） */
    private String shiftCode;

    /** 记录人 */
    private String recorderId;

    /** 备注 */
    private String remark;

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
