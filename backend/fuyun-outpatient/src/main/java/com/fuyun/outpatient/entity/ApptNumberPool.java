package com.fuyun.outpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.PoolStatus;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 号源池行实体（outpatient.appt_number_pool，M03 Spec §5 方案 3.1）：号源权威库存行（排班×号别×
 * 号段粒度），持有总量/渠道配额/已用量/乐观锁版本。扣减与回补走 casOccupy/casRelease 注解 CAS
 * （version 显式谓词），Redis 池键为第一道闸、本表条件更新为第二道闸（Spec 3.2 双道闸）。
 * 加号授权经 casAddExtraQuota 增量 total_quota，加号占用计数 extra_used（Task 5 挂号消费）。
 * 线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("outpatient.appt_number_pool")
public class ApptNumberPool {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属排班日历 id（schedule.id） */
    private Long scheduleId;

    /** 号别词表（ApptType：GENERAL/EXPERT/SPECIAL_DISEASE/EMERGENCY/REVISIT） */
    private ApptType apptType;

    /** 号段开始时刻 */
    private LocalTime slotStart;

    /** 号段结束时刻（须晚于 slotStart，库端 CHECK 兜底） */
    private LocalTime slotEnd;

    /** 号总数（加号授权经 casAddExtraQuota 增量），非空且 >0 */
    private Integer totalQuota;

    /** 线上/窗口/自助/预留 JSON 配额百分比（库默认 '{"PORTAL":60,"WINDOW":30,"KIOSK":5,"RESERVED":5}'；P1 仅 PORTAL/WINDOW 通道计数） */
    private String channelQuota;

    /** 已用号数（CAS 余量谓词 used_count &lt; total_quota 的左操作数），库默认 0 */
    private Integer usedCount;

    /** 加号已用数（加号授权走 total_quota 增量，本列计加号占用，Task 5 挂号消费），库默认 0 */
    private Integer extraUsed;

    /** 乐观锁版本（casOccupy/casRelease/casAddExtraQuota 单调递增；CAS 经注解 SQL 显式谓词，非 @Version 拦截器） */
    private Integer version;

    /** 池行状态机（PoolStatus：ACTIVE/STOPPED/EXPIRED） */
    private PoolStatus status;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护（触发器刷新） */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入（放号生成） */
    private String createdBy;

    /** 审计列：操作人应用层注入（停诊/恢复/加号 CAS updated_by） */
    private String updatedBy;

    /** 逻辑删标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Short deleted;
}
