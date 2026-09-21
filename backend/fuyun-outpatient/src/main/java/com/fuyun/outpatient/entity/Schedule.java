package com.fuyun.outpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.ScheduleStatus;
import com.fuyun.outpatient.enums.SessionType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 排班日历实体（outpatient.schedule，M03 Spec §5）：由排班模板按放号规则批量生成的「某日×时段」
 * 开诊行，uk_schedule（template_id+sched_date+session）为放号幂等锚；停诊整日历生效（status
 * CAS 迁移）并联动号源池行 STOPPED。线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("outpatient.schedule")
public class Schedule {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 母本模板 id（schedule_template.id） */
    private Long templateId;

    /** 排班日期 */
    private LocalDate schedDate;

    /** 门诊时段（SessionType：MORNING/AFTERNOON/EVENING） */
    private SessionType session;

    /** 开诊科室编码 */
    private String deptCode;

    /** 出诊医生 id */
    private String doctorId;

    /** 号别词表（ApptType，同模板） */
    private ApptType apptType;

    /** 当日总号数（生成自 slot_quota；加号经池行 total_quota 增量），非空且 >0 */
    private Integer totalQuota;

    /** 已用号数（池行 used_count 聚合展示口径），库默认 0 */
    private Integer usedQuota;

    /** 诊疗室，可空 */
    private String room;

    /** 排班状态机（ScheduleStatus：NORMAL/STOPPED；停诊经 casStatus 注解 CAS 迁移） */
    private ScheduleStatus status;

    /** 停诊原因（status=STOPPED 时应用层必填，schedule.stopped 事件携带），可空 */
    private String stopReason;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护（触发器刷新） */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入（放号生成） */
    private String createdBy;

    /** 审计列：操作人应用层注入（停诊/恢复 CAS updated_by） */
    private String updatedBy;

    /** 逻辑删标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Short deleted;
}
