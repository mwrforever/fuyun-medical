package com.fuyun.outpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.SessionType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 排班模板实体（outpatient.schedule_template，M03 Spec §5 方案 3.1）：科室×医生×时段×号别的每周
 * 出诊规律配置行，放号生成（POST /schedules/generate）按 week_pattern 位串×日期区间展开为排班
 * 日历与号源池行的母本。同一模板可展开多日多行，本实体只承载配置面。线程安全：可变实体仅
 * service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("outpatient.schedule_template")
public class ScheduleTemplate {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 开诊科室编码（M01 组织域科室 code） */
    private String deptCode;

    /** 出诊医生 id（sys_employee，演示链路同 sys_user.id） */
    private String doctorId;

    /** 模板生效日（含当日） */
    private LocalDate effFrom;

    /** 模板失效日（含当日）；NULL=长期有效 */
    private LocalDate effTo;

    /** 每周出诊位串：7 位 0/1，位序周一~周日（如 1100000=周一/周二出诊，CHECK 校验格式） */
    private String weekPattern;

    /** 门诊时段（SessionType：MORNING/AFTERNOON/EVENING） */
    private SessionType session;

    /** 号别词表（ApptType：GENERAL/EXPERT/SPECIAL_DISEASE/EMERGENCY/REVISIT，=字典 outpatient.appt-type 条目） */
    private ApptType apptType;

    /** 号段开始时刻 */
    private LocalTime slotStart;

    /** 号段结束时刻（须晚于 slotStart，库端 CHECK 兜底） */
    private LocalTime slotEnd;

    /** 该时段号总数（生成池行 total_quota 的母本），非空且 >0 */
    private Integer slotQuota;

    /** 诊疗室，可空 */
    private String room;

    /** T+N 放号周期（天），库默认 7 */
    private Integer releaseDays;

    /** 每日放号时点，库默认 07:00 */
    private LocalTime releaseTime;

    /** 模板状态词表：ACTIVE 启用/STOPPED 停用（停用模板不参与放号展开；Task 4 无停用端点，字符串承载） */
    private String status;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护（触发器刷新） */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入（登记端点） */
    private String createdBy;

    /** 审计列：操作人应用层注入（更新端点） */
    private String updatedBy;

    /** 逻辑删标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Short deleted;
}
