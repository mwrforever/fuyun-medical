package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 护理评估单实体（nursing.nursing_assessment，V806）：五量表（BRADEN/MORSE/NRS/BARTHEL/MEWS）
 * 评估的落库载体。量表定义以模块内 Java 常量承载（Spec :249），本表仅存条目应答快照（answers
 * JSONB 文本，pgjdbc getString 直读——NursingWardConfig 同款形态）与判级结果；assessed_at 为
 * 临床实际评估时刻（业务时间，请求携带强校验），created_at/updated_at 为服务器审计时钟。
 * 高风险联动面：triggered_task_ref 引用自动生成的防范任务（nursing_task task_type=PREVENTION）；
 * adverse_event_ref 为事件后回评引用（FU-M05-09 归 P2，P1 恒空列）。
 */
@Getter
@Setter
@TableName("nursing.nursing_assessment")
public class NursingAssessment {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 评估单号（AS+yyyyMMdd+5 位流水，发号器统一取号；uk_nursing_assessment_no 唯一） */
    private String assessNo;

    /** 患者主索引（在区行归一后服务端装配，不信客户端） */
    private Long patientId;

    /** 住院就诊号（I 型 14 位） */
    private String visitId;

    /** 病区编码（在区行归一后服务端装配） */
    private String wardId;

    /** 量表类型（ScaleType code：BRADEN/MORSE/NRS/BARTHEL/MEWS） */
    private String scaleType;

    /** 条目应答快照（JSONB 文本：{"itemCode": score, ...}） */
    private String answers;

    /** 量表总分 */
    private Integer totalScore;

    /** 风险等级（RiskLevel code：HIGH/MEDIUM/LOW） */
    private String riskLevel;

    /** 评估时点（临床实际评估时刻，业务时间口径；服务端强校验不晚于当前、不早于入区） */
    private OffsetDateTime assessedAt;

    /** 评估人 */
    private String assessedBy;

    /** 下次复评计划（assessedAt + 风险等级复评周期：24h/72h/168h） */
    private OffsetDateTime nextAssessPlan;

    /** 防范任务引用（nursing_task.task_no；高危联动生成后同事务回填，非高危恒空） */
    private String triggeredTaskRef;

    /** 事件后回评引用（FU-M05-09 归 P2，P1 恒空列） */
    private String adverseEventRef;

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
