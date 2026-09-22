package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 责任护士分配实体（nursing.nurse_assignment，V801，FU-M05-01）：护士↔床位/患者 × 班次的分配关系，
 * 大屏管床与任务派发依据。唯一性由两条部分唯一索引兜底：同床位同班次同生效日唯一（uk_assignment_bed_shift）、
 * 同患者同班次同生效日唯一（uk_assignment_patient_shift），均限定 ACTIVE 态；撤销走 status=CANCELLED
 * （逻辑不删行，留痕交接班追溯）。状态/类型以 String 承载 code（值域见 enums 包）。
 */
@Getter
@Setter
@TableName("nursing.nurse_assignment")
public class NurseAssignment {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 病区编码 */
    private String wardId;

    /** 护士标识（M01 用户标识，与审计列/操作者口径统一 VARCHAR(64)） */
    private String nurseId;

    /** 分配类型（AssignmentType code：PRIMARY 责任组 / BED 管床） */
    private String assignmentType;

    /** 班次 code（取 nursing_ward_config.shift_definitions） */
    private String shiftCode;

    /** 管床床位号（assignment_type=BED 时必填） */
    private String bedNo;

    /** 责任患者（assignment_type=PRIMARY 时必填；MERGED 收敛主档口径同病区患者视图） */
    private Long patientId;

    /** 生效日期 */
    private LocalDate validFrom;

    /** 失效日期（空=长期） */
    private LocalDate validTo;

    /** 分配状态：ACTIVE 生效 / CANCELLED 已撤销 */
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
