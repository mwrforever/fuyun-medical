package com.fuyun.nursing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 护理任务统一载体实体（nursing.nursing_task，V805）：给药/输液/翻身/巡视/评估提醒等任务
 * 的最小载体（FU-M05-07 任务工作台——分组/认领/模板批量生成归 P2）。overdue_flag +
 * escalation_count 为「动作式逾期」落点（非状态）：P1 由查询侧惰性判定单次递增（Spec :127 +
 * §12-3），P2 由延迟队列驱动发布 nursing.task.overdue。cancel_reason 取消必填留痕；
 * bed_no/assigned_nurse 为冗余展示面（空=未指派，按责任组展示归任务工作台）。
 */
@Getter
@Setter
@TableName("nursing.nursing_task")
public class NursingTask {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 任务业务号（TK+yyyyMMdd+5 位流水，发号器统一取号；uk_nursing_task_no 唯一） */
    private String taskNo;

    /** 患者主索引 */
    private Long patientId;

    /** 住院就诊号（I 型 14 位） */
    private String visitId;

    /** 病区编码（任务清单检索键） */
    private String wardId;

    /** 床位号（冗余展示，可空） */
    private String bedNo;

    /** 任务类型（TaskType code：MEDICATION/INFUSION_CARE/TURN/PATROL/SPECIMEN/IO_MONITOR/IOT_LINKAGE/ASSESS_REMIND/MANUAL/PREVENTION） */
    private String taskType;

    /** 任务来源（TaskSource code：ORDER_PLAN/INFUSION_ALARM/IOT_LINKAGE/ROUTINE/MANUAL/ASSESSMENT） */
    private String source;

    /** 来源引用（执行单号/告警号/规则号/评估单号，可空） */
    private String sourceRef;

    /** 计划时间（服务器时间口径入参；逾期判定基准） */
    private OffsetDateTime planTime;

    /** 责任护士（空=未指派，由任务列表按责任组展示） */
    private String assignedNurse;

    /** 优先级（TaskPriority code：HIGH/NORMAL/LOW） */
    private String priority;

    /** 逾期标记（动作式，非状态；查询侧惰性判定置位） */
    private Boolean overdueFlag;

    /** 升级次数（P1 惰性判定仅首次递增；P2 延迟队列驱动升级链扩展） */
    private Integer escalationCount;

    /** 完成时间（complete CAS 盖章；巡视打卡直落终态时同打卡时刻） */
    private OffsetDateTime completedAt;

    /** 取消原因（取消必填留痕） */
    private String cancelReason;

    /** 任务状态（TaskStatus code：PENDING/IN_PROGRESS/COMPLETED/CANCELLED；IN_PROGRESS 为 P1 声明态） */
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
