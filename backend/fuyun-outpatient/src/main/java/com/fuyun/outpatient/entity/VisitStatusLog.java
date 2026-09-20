package com.fuyun.outpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.outpatient.enums.VisitStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 就诊状态迁移日志实体（outpatient.visit_status_log，03 Spec 红线 5「每迁必记」）：只增表——零更新
 * 零逻辑删，无审计五列与触发器（V201 DDL 口径），occurred_at 由库端 DEFAULT now() 维护。迁移前置
 * 校验归状态机单点（Task 8 落位），本实体仅承载轨迹留痕。线程安全：可变实体仅 service 事务内使用，
 * 不出数据层。
 */
@Getter
@Setter
@TableName("outpatient.visit_status_log")
public class VisitStatusLog {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 就诊号（uk_visit_id 同源，迁移轨迹回放维度） */
    private String visitId;

    /** 迁出态（VisitStatus code） */
    private VisitStatus fromStatus;

    /** 迁入态（VisitStatus code） */
    private VisitStatus toStatus;

    /** 迁移原因，可空 */
    private String reason;

    /** 操作者（portal 链路取哨兵值 PORTAL） */
    private String operator;

    /** 迁移时刻（库端 DEFAULT now() 维护；MP 插入空值省列交库默认） */
    private OffsetDateTime occurredAt;
}
