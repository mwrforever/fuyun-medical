package com.fuyun.pharmacy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 药师审方任务实体（pharmacy.review_task，V1000）：住院用药审方工作台任务行（薄切片=全部
 * 人工审方，规则引擎 P3）；uk_review_medication 保证一快照一任务（「重复消费仅一任务」硬
 * 防线），驳回后重提经同任务复位重开（非新建）。三态小状态机 PENDING→APPROVED/REJECTED，
 * PENDING 唯一可决出边（CAS 兜底并发）。
 */
@Getter
@Setter
@TableName("pharmacy.review_task")
public class ReviewTask {

    /** 雪花主键（MP ASSIGN_ID 插入期回填；即审方回执 auditNo 审核方引用） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联用药快照 id（pharmacy.order_medication 主键；uk 唯一） */
    private Long orderMedicationId;

    /** 申请科室（M01 组织 code；事件契约未携带，随 CF-6 契约扩展回填，薄切片期 NULL） */
    private String applyDept;

    /** 申请医生（员工工号；事件契约未携带，随 CF-6 契约扩展回填，薄切片期 NULL） */
    private String applyDoctor;

    /** 任务状态（ReviewTaskStatus code：PENDING/APPROVED/REJECTED） */
    private String status;

    /** 审方药师（员工工号，决策回写；即审方回执 auditOperator） */
    private String pharmacistId;

    /** 药师意见（驳回必附——回执 rejectReason；通过可选） */
    private String opinion;

    /** 决策时刻（审方通过/驳回回写） */
    private OffsetDateTime decidedAt;

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
