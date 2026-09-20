package com.fuyun.outpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 爽约信用记录实体（outpatient.appt_credit_record，M03 Spec §4/§5）：爽约/超时信用台账——窗口内
 * NO_SHOW 计数达阈值即写 restrict_from~restrict_to 限约区间（预约侧 OP-1006 拦截依据），解除
 * （到期自动/管理员手工，Task 6 交付）经 release_reason 留痕。action 词表 NO_SHOW/TIMEOUT_CANCEL
 * 以常量承载（无专属枚举，任务划界五枚举之外）。线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("outpatient.appt_credit_record")
public class ApptCreditRecord {

    /** 信用动作：爽约超时（与 appointment NO_SHOW 同刻产生） */
    public static final String ACTION_NO_SHOW = "NO_SHOW";

    /** 信用动作：时限外取消（退号链产生，Task 6 消费） */
    public static final String ACTION_TIMEOUT_CANCEL = "TIMEOUT_CANCEL";

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 患者主索引（爽约主体归一后主档） */
    private Long patientId;

    /** 动作词表：NO_SHOW 爽约超时 / TIMEOUT_CANCEL 时限外取消（常量见本类） */
    private String action;

    /** 发生时刻 */
    private OffsetDateTime occurredAt;

    /** 记录时采用的统计窗口天数（参数留痕，预约拦截按此窗口回溯计数） */
    private Integer windowDays;

    /** 限约起始日（命中阈值时写当日），可空 */
    private LocalDate restrictFrom;

    /** 限约截止日（含当日），可空 */
    private LocalDate restrictTo;

    /** 解除原因（信用解除留痕），可空 */
    private String releaseReason;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护（触发器刷新） */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入（超时链路固定 system） */
    private String createdBy;

    /** 审计列：操作人应用层注入 */
    private String updatedBy;

    /** 逻辑删标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Short deleted;
}
