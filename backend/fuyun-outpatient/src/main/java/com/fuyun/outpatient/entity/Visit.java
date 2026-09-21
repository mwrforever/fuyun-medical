package com.fuyun.outpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.enums.VisitType;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 就诊记录实体（outpatient.visit，M03 Spec §4）：visit 主状态机载体，visit_id 为 CF-3 冻结结构
 * （O 型 14 位，M03 唯一签发，签发后不可变）。全部状态迁移经状态机单点校验+visit_status_log 每迁
 * 必记（03 Spec 红线 5，状态机随 Task 8 落位）；声明态（IN_EXECUTION/PENDING_MEDICATION/NO_SHOW）
 * P1 不迁移。线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("outpatient.visit")
public class Visit {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 就诊号（O+yyyyMMdd+5 位流水，uk_visit_id 唯一，CF-3 冻结） */
    private String visitId;

    /** 患者主索引（与 visit_id 同刻落库，CF-3 结论④） */
    private Long patientId;

    /** 关联预约单 id（appointment.id；当日挂号亦建 appointment），可空 */
    private Long apptId;

    /** 开诊科室编码（候诊队列与统计维度锚点） */
    private String deptCode;

    /** 接诊医生 id（按排班回填），可空 */
    private String doctorId;

    /** 就诊类型（VisitType：EMERGENCY/GENERAL/SPECIAL/INTERNET/MDT/OTHER） */
    private VisitType visitType;

    /** 是否复诊（0/1；号别 REVISIT 落 1），库默认 0 */
    private Short isRevisit;

    /** 急诊分级（Ⅰ~Ⅳ=1~4，分诊台写入），可空 */
    private Integer triageLevel;

    /** 医保类型，可空 */
    private String insuranceType;

    /** 绿通标记（声明列，P1 恒 0），库默认 0 */
    private Short greenChannelFlag;

    /** 挂号/取号时间 */
    private OffsetDateTime registeredAt;

    /** 报到时间（分诊台/自助签到，国标采集），可空 */
    private OffsetDateTime checkedInAt;

    /** 接诊时间（国标采集），可空 */
    private OffsetDateTime admittedAt;

    /** 诊毕时间，可空 */
    private OffsetDateTime finishedAt;

    /** 离院去向（国标代码 1~7/9，诊毕写入），可空 */
    private String disposition;

    /** 诊毕操作者，可空 */
    private String finishOperator;

    /** 就诊状态机（VisitStatus：REGISTERED/WAITING/IN_CONSULT/PENDING_FEE/声明态/FINISHED/CANCELLED/NO_SHOW） */
    private VisitStatus status;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护（触发器刷新） */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入 */
    private String createdBy;

    /** 审计列：操作人应用层注入 */
    private String updatedBy;

    /** 逻辑删标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Short deleted;
}
