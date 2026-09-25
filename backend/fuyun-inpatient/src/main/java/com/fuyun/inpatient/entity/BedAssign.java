package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 床位占用流水实体（inpatient.bed_assign，V903）——只增表：床位历史占用回溯依据。started_at
 * 开账、ended_at 由转移/出院动作闭合（NULL=未闭合在用行）；未闭合行每床至多一条
 * （uk_bed_assign_open 部分唯一索引兜底并发开账）。禁物理 UPDATE 业务列外的改写与逻辑删
 * 已闭合行（历史可溯红线）。
 */
@Getter
@Setter
@TableName("inpatient.bed_assign")
public class BedAssign {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 占用床位 id（bed 1:N bed_assign，未闭合行每床至多一条） */
    private Long bedId;

    /** 住院就诊号（I 型 14 位；占用主体，历史占用回溯键） */
    private String visitId;

    /** 占用类型 code（AssignType：ADMISSION 入院分配/BED_CHANGE 转床/WARD_TRANSFER 转科转入） */
    private String assignType;

    /** 占用开始时点（库端时间） */
    private OffsetDateTime startedAt;

    /** 占用结束时点（转移/出院动作闭合；NULL=未闭合在用行） */
    private OffsetDateTime endedAt;

    /** 操作者（开账护士/系统联动操作者） */
    private String operator;

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
