package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 护理不良事件实体（nursing.adverse_event，V1107）：上报-处置-关闭全周期载体。
 * 匿名通道（is_anonymous=true 时 reporter_id 落 NULL——非惩罚文化红线，出参/统计面
 * 零惩罚字段）；I/II 级 24 小时强制上报时限（report_deadline=occurred_at+24h，超时
 * deadline_met=false 留痕不拒绝）；关闭随附 RCA 根因分析与整改措施（流程改进面）。
 */
@Getter
@Setter
@TableName("nursing.adverse_event")
public class AdverseEvent {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 不良事件业务号（AE+yyyyMMdd+5 位流水，本模块 NursingSeqGate 签发；uk_adverse_event_no 唯一） */
    private String eventNo;

    /** 事件类别（AdverseEventCategory code 八词表） */
    private String category;

    /** 严重度分级（SeverityClass code：I 最重~IV 最轻；I/II 级触发 24 小时上报时限） */
    private String severityClass;

    /** 严重度等级（SeverityGrade code：A 无害~E 死亡；与分级正交） */
    private String severityGrade;

    /** 发生病区编码（与护理域各表同宽口径） */
    private String wardId;

    /** 住院就诊号（可空：设施类事件可无就诊主体） */
    private String visitId;

    /** 患者主索引（可空，同上） */
    private Long patientId;

    /** 事件发生时点（上报表单据实填报，非落库时点） */
    private OffsetDateTime occurredAt;

    /** 事件经过（上报人据实描述） */
    private String eventSummary;

    /** 处置情况（上报时初步处置记录；处置阶段更新为处置进展） */
    private String handlingNote;

    /** 上报人员工 ID（匿名通道上报为 NULL——配套 is_anonymous=true） */
    private Long reporterId;

    /** 匿名上报标识（true=匿名通道，不落上报人） */
    private Boolean isAnonymous;

    /** 上报时限基准（I/II 级=occurred_at+24h；III/IV 级不预置为 NULL） */
    private OffsetDateTime reportDeadline;

    /** 时限达成（上报/处置时点判定落值；未判定为 NULL——超时只留痕不拒绝，非惩罚原则） */
    private Boolean deadlineMet;

    /** 处置状态（AdverseEventStatus code 三态；落库默认 REPORTED） */
    private String status;

    /** 处置责任人员工 ID（进入 HANDLING 落值） */
    private Long handlerId;

    /** 根因分析（RCA）记录（关闭时按需补录） */
    private String rcaNote;

    /** 整改措施（关闭时按需补录——流程改进面） */
    private String correctiveAction;

    /** 创建时刻（DB now() 默认） */
    private OffsetDateTime createdAt;

    /** 更新时刻（DB now() 默认 + 触发器维护） */
    private OffsetDateTime updatedAt;

    /** 创建者（REST 链路操作者回退口径） */
    private String createdBy;

    /** 更新者（REST 链路操作者/动作主体承载） */
    private String updatedBy;

    /** 逻辑删标记 */
    @TableLogic
    private Integer deleted;
}
