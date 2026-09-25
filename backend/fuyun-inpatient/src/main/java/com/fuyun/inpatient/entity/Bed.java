package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 床位实体（inpatient.bed，V903）——床位主数据：五态状态机（BedStatus）由 CAS 条件更新 +
 * 影响行数判定驱动（防重复占床硬防线：仅 FREE/RESERVED 可占床）。bed_attr 承载包床/加床
 * 计费属性标记（非状态位）；visit_id 为当前占用冗余列（权威在 bed_assign 未闭合行，预占
 * 不绑定——登记确认才签发 visit_id）。床位图（bedMap）数据源。
 */
@Getter
@Setter
@TableName("inpatient.bed")
public class Bed {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 床号（病区内唯一，uk_bed_ward_no 复合承载） */
    private String bedNo;

    /** 归属病区编码（M01 组织机构 code；转床轻量路径限定同病区） */
    private String wardId;

    /** 床位属性 code（BedAttr：NORMAL 普通/PRIVATE 包床/EXTRA 加床；包床为计费属性非状态） */
    private String bedAttr;

    /** 性别限制（MALE 男/FEMALE 女/null 不限） */
    private String allowGender;

    /** 当前占用住院就诊号（I 型 14 位；冗余列权威在 bed_assign 未闭合行；预占不绑定） */
    private String visitId;

    /** 状态 code（BedStatus：FREE/RESERVED/OCCUPIED/DISINFECTING/MAINTENANCE） */
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
