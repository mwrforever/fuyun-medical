package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 住院医嘱状态迁移日志实体（inpatient.order_status_log，V905）——状态机迁移留痕（只增）：
 * OrderStateMachineService.transition 每次迁移自动落一行（回接落库，调用方不另写）；
 * 重整/撤回/停嘱原因亦在此留痕（from_status=to_status 表示无迁移动作留痕，如医嘱重整）。
 * 业务面仅 INSERT（流水先例形态对齐 billing.insurance_call_log）。
 */
@Getter
@Setter
@TableName("inpatient.order_status_log")
public class OrderStatusLog {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属医嘱主键（medical_order.id） */
    private Long orderId;

    /** 迁移前状态（OrderStatus 八态 code；重整留痕行与 to_status 同值） */
    private String fromStatus;

    /** 迁移后状态（OrderStatus 八态 code；重整留痕行与 from_status 同值） */
    private String toStatus;

    /** 迁移/留痕原因（审核通过/驳回/停嘱理由/作废理由/撤回/重整等） */
    private String reason;

    /** 操作者员工 ID（触发迁移/留痕的主体） */
    private String operator;

    /** 发生时点（服务器时间；M06 回执驱动的迁移取回执时点） */
    private OffsetDateTime occurredAt;

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
