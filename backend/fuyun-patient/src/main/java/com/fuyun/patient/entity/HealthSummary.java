package com.fuyun.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 健康档案 1:1 聚合（uk patient_id）；summary_updated_at 为事件时间锚点。
 *
 * <p>聚合行懒创建：首笔明细写入（touchSummary）时才落库，查询路径无行不建行；
 * 一档一份（uk_health_summary_patient 部分唯一索引兜底，逻辑删行不占用唯一性），
 * summary_updated_at 由应用层维护，作为 health-summary.updated 事件载荷时间锚点。
 */
@Getter
@Setter
@TableName("patient.health_summary")
public class HealthSummary {

    /** 聚合行 id（雪花，MP ASSIGN_ID 插入时生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 患者主索引（1:1，uk 兜底） */
    private Long patientId;

    /** 血型（字典 code；档案口径，与 patient.blood_type 人口属性列并存） */
    private String bloodType;

    /** RH 血型：POSITIVE/NEGATIVE */
    private String rhType;

    /** 既往史 */
    private String pastHistory;

    /** 家族史 */
    private String familyHistory;

    /** 摘要最近变更时刻（应用层落，事件载荷时间锚点） */
    private OffsetDateTime summaryUpdatedAt;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护，应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：默认 'system' */
    private String createdBy;

    /** 更新人：默认 'system' */
    private String updatedBy;

    /** 逻辑删除标记（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
