package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.billing.enums.FeeSplitType;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 费用归属切分实体（billing.fee_ownership_split，V1001）：inpatient 住院域事件驱动的费用归属
 * 时间线落点——入科起费锚点（附当日床位费计价入口）、转科切分点（from/to 病区齐备）、出院停费
 * 标记（日切在院判定面）。同类行业务级幂等由消费侧存在性守卫承载（事件重投零重复行）。
 * 线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("billing.fee_ownership_split")
public class FeeOwnershipSplit {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** CF-3 住院就诊号（I 型 14 位） */
    private String visitId;

    /** 患者主索引（日切床位费计价命令入参） */
    private Long patientId;

    /** 转出病区编码（入科锚点 NULL） */
    private String fromWardId;

    /** 转入病区编码（出院停费标记 NULL） */
    private String toWardId;

    /** 切分类型（FeeSplitType 三值） */
    private FeeSplitType splitType;

    /** 切分时点（事件载荷时点透传，UTC） */
    private OffsetDateTime splitAt;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护 */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入 */
    private String createdBy;

    /** 审计列 */
    private String updatedBy;

    /** 逻辑删标记（@TableLogic 全局配置） */
    @TableLogic
    private Short deleted;
}
