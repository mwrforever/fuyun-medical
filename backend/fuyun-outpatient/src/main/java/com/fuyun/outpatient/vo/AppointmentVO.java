package com.fuyun.outpatient.vo;

import com.fuyun.outpatient.enums.ApptChannel;
import com.fuyun.outpatient.enums.ApptStatus;
import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.FeeStatusType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * 预约/挂号单出参（POST /appointments 与 portal 预约直出形态）：业务号/visit_id 等雪花外业务号
 * string 承载（A.3-6）；状态/渠道/费态以枚举 code 序列化（@JsonValue）。
 *
 * @param id          预约单主键；来源：落库回填
 * @param apptNo      预约单业务号（AP+yyyyMMdd+6 位流水）
 * @param patientId   患者主索引（归一后主档）
 * @param scheduleId  排班日历 id
 * @param poolId      号源池行 id
 * @param apptType    号别 code
 * @param schedDate   排班日期
 * @param slotStart   号段开始时刻
 * @param slotEnd     号段结束时刻
 * @param channel     预约渠道 code
 * @param feeStatus   挂号费状态 code
 * @param payDeadline 支付时限（PORTAL 占位有值；窗口/自助为 null）
 * @param visitId     就诊号（取号/当日挂号后回填；预约占位为 null）
 * @param status      预约单状态 code（RESERVED/TAKEN/...）
 */
public record AppointmentVO(
        Long id,
        String apptNo,
        Long patientId,
        Long scheduleId,
        Long poolId,
        ApptType apptType,
        LocalDate schedDate,
        LocalTime slotStart,
        LocalTime slotEnd,
        ApptChannel channel,
        FeeStatusType feeStatus,
        OffsetDateTime payDeadline,
        String visitId,
        ApptStatus status) {}
