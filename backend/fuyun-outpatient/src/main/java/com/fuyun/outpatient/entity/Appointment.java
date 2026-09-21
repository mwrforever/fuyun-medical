package com.fuyun.outpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.outpatient.enums.ApptChannel;
import com.fuyun.outpatient.enums.ApptStatus;
import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.FeeStatusType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 预约/挂号单实体（outpatient.appointment，M03 Spec §4）：全渠道统一预约单——PORTAL 渠道 RESERVED
 * 占位（pay_deadline 支付时限），WINDOW/KIOSK 一步直达 TAKEN 并经 casTake 回填 visit_id。状态迁移走
 * casStatus/casTake 注解 CAS（超卖由 uk_appt_patient 限购唯一索引+池行 CAS 兜底）。资金无涉红线
 * （裁决 7）：fee_status/fee_settlement_id 仅存结算状态与回填锚，零金额列。线程安全：可变实体仅
 * service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("outpatient.appointment")
public class Appointment {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 预约单业务号（AP+yyyyMMdd+6 位流水），uk_appt_no 唯一 */
    private String apptNo;

    /** 患者主索引（解析归一后主档，M02 红线 1） */
    private Long patientId;

    /** 排班日历 id（schedule.id） */
    private Long scheduleId;

    /** 号源池行 id（appt_number_pool.id，超时回池定位锚） */
    private Long poolId;

    /** 开诊科室编码（经 schedule join 冗余，uk_appt_patient 限购谓词维度） */
    private String deptCode;

    /** 号别词表（ApptType，与池行 appt_type 同源） */
    private ApptType apptType;

    /** 排班日期（限购谓词维度） */
    private LocalDate schedDate;

    /** 号段开始时刻 */
    private LocalTime slotStart;

    /** 号段结束时刻 */
    private LocalTime slotEnd;

    /** 预约渠道（ApptChannel：WINDOW/KIOSK/PORTAL/MINIAPP/CONSULT/EXTERNAL） */
    private ApptChannel channel;

    /** 挂号费状态（FeeStatusType：UNPAID/PAID/REFUNDED），库默认 UNPAID */
    private FeeStatusType feeStatus;

    /** 挂号费结算单 id（回填，退号退费定位锚；M13 权威） */
    private Long feeSettlementId;

    /** 支付时限（PORTAL 占位=now()+appointmentTimeout；casTake 超时守卫谓词），可空 */
    private OffsetDateTime payDeadline;

    /** 改期链原预约单号（改期=退旧号新，Task 6 消费），可空 */
    private String rescheduleOf;

    /** 就诊号（取号后经 casTake 回填，O 型 14 位），可空 */
    private String visitId;

    /** 预约单状态机（ApptStatus：RESERVED/TAKEN/CANCELLED/NO_SHOW） */
    private ApptStatus status;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护（触发器刷新） */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入（portal 匿名链路取哨兵值 PORTAL，裁决 13） */
    private String createdBy;

    /** 审计列：操作人应用层注入 */
    private String updatedBy;

    /** 逻辑删标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Short deleted;
}
