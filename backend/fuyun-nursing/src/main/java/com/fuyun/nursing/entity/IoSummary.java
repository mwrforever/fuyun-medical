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
 * 出入量小结实体（nursing.io_summary，V804）：班次小结与 24 小时总结权威行（Spec :112）。
 * 幂等键 (visit_id, summary_type, period_start, shift_key)——shift_key 为 DB 生成列
 * （COALESCE(shift_code,'') STORED）承载唯一约束第四维，本实体不映射该生成列
 * （GENERATED ALWAYS 禁写入，MP 插入列清单不得包含，VitalSignRecord.site_key 同款）。
 * 生成小结后写体温单 DAILY_VALUE 条目并将条目 id 回填 chart_entry_ref（红双线标识由前端渲染，
 * 调研依据 6）。数量落库 BigDecimal NUMERIC(12,2)（D-18：VO 侧 toPlainString）。
 */
@Getter
@Setter
@TableName("nursing.io_summary")
public class IoSummary {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 住院就诊号（I 型 14 位；在区校验经 IWardMetaService） */
    private String visitId;

    /** 患者主索引（在区行归一后服务端装配，不信客户端） */
    private Long patientId;

    /** 病区编码（在区行归一后服务端装配） */
    private String wardId;

    /** 小结类型（IoSummaryType code：SHIFT 班次小结 / 24H 24 小时总结） */
    private String summaryType;

    /** 统计周期起（班次开始时刻或当日 00:00；幂等键第三维） */
    private OffsetDateTime periodStart;

    /** 统计周期止（班次结束时刻或次日 00:00，窗口含头不含尾） */
    private OffsetDateTime periodEnd;

    /** 总入量（NUMERIC(12,2)） */
    private BigDecimal totalIntake;

    /** 总出量（NUMERIC(12,2)） */
    private BigDecimal totalOutput;

    /** 平衡值 = 总入量 - 总出量（NUMERIC(12,2)） */
    private BigDecimal balance;

    /** 班次 code（summary_type=SHIFT 时必填；24H 恒空——生成列 shift_key 落空串键） */
    private String shiftCode;

    /** 体温单 DAILY_VALUE 条目引用（小结链同事务回填） */
    private Long chartEntryRef;

    /** 记录人 */
    private String recorderId;

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
