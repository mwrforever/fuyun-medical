package com.fuyun.outpatient.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.outpatient.entity.Appointment;
import com.fuyun.outpatient.entity.ApptCreditRecord;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.enums.ApptChannel;
import com.fuyun.outpatient.enums.ApptStatus;
import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.FeeStatusType;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.enums.VisitType;
import com.fuyun.outpatient.vo.AppointmentVO;
import com.fuyun.outpatient.vo.ApptCreditVO;
import com.fuyun.outpatient.vo.VisitVO;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 预约域转换器单测（BUG-09 迁入守护）：三段实体→出参直映与原手写逐字段等价——全字段断言锁位次
 * 语义（record 位置参数增删/调序在此即失败），并守护可空字段的 null 传播（原手写直传 null 语义）。
 */
class AppointmentConverterTest {

    @Test
    @DisplayName("预约单直映：14 字段全量等价（含枚举/日期时间原样承载）")
    void appointmentMapsAllFields() {
        Appointment entity = new Appointment();
        entity.setId(9001L);
        entity.setApptNo("AP20260928000001");
        entity.setPatientId(77L);
        entity.setScheduleId(31L);
        entity.setPoolId(15L);
        entity.setApptType(ApptType.EXPERT);
        entity.setSchedDate(LocalDate.of(2026, 9, 28));
        entity.setSlotStart(LocalTime.of(9, 0));
        entity.setSlotEnd(LocalTime.of(9, 30));
        entity.setChannel(ApptChannel.PORTAL);
        entity.setFeeStatus(FeeStatusType.UNPAID);
        entity.setPayDeadline(OffsetDateTime.parse("2026-09-28T09:15:00+08:00"));
        entity.setVisitId("O2026092800001");
        entity.setStatus(ApptStatus.RESERVED);

        AppointmentVO vo = AppointmentConverter.INSTANCE.toAppointmentVO(entity);

        assertThat(vo.id()).isEqualTo(9001L);
        assertThat(vo.apptNo()).isEqualTo("AP20260928000001");
        assertThat(vo.patientId()).isEqualTo(77L);
        assertThat(vo.scheduleId()).isEqualTo(31L);
        assertThat(vo.poolId()).isEqualTo(15L);
        assertThat(vo.apptType()).isEqualTo(ApptType.EXPERT);
        assertThat(vo.schedDate()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(vo.slotStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(vo.slotEnd()).isEqualTo(LocalTime.of(9, 30));
        assertThat(vo.channel()).isEqualTo(ApptChannel.PORTAL);
        assertThat(vo.feeStatus()).isEqualTo(FeeStatusType.UNPAID);
        assertThat(vo.payDeadline()).isEqualTo(OffsetDateTime.parse("2026-09-28T09:15:00+08:00"));
        assertThat(vo.visitId()).isEqualTo("O2026092800001");
        assertThat(vo.status()).isEqualTo(ApptStatus.RESERVED);
    }

    @Test
    @DisplayName("预约单 null 传播：PORTAL 占位前的 payDeadline 与未取号 visitId 直传 null")
    void appointmentPropagatesNullables() {
        Appointment entity = new Appointment();
        entity.setId(9002L);
        entity.setApptNo("AP20260928000002");
        entity.setPatientId(78L);
        entity.setScheduleId(31L);
        entity.setPoolId(16L);
        entity.setApptType(ApptType.GENERAL);
        entity.setSchedDate(LocalDate.of(2026, 9, 28));
        entity.setSlotStart(LocalTime.of(10, 0));
        entity.setSlotEnd(LocalTime.of(10, 30));
        entity.setChannel(ApptChannel.WINDOW);
        entity.setFeeStatus(FeeStatusType.UNPAID);
        entity.setStatus(ApptStatus.TAKEN);

        AppointmentVO vo = AppointmentConverter.INSTANCE.toAppointmentVO(entity);

        // 原手写直传语义：窗口渠道无支付时限、取号前无就诊号——null 必须原样传播不得转默认值
        assertThat(vo.payDeadline()).isNull();
        assertThat(vo.visitId()).isNull();
        assertThat(vo.status()).isEqualTo(ApptStatus.TAKEN);
    }

    @Test
    @DisplayName("爽约信用直映：8 字段全量等价（含限约区间与解除留痕）")
    void creditMapsAllFields() {
        ApptCreditRecord entity = new ApptCreditRecord();
        entity.setId(5001L);
        entity.setPatientId(77L);
        entity.setAction(ApptCreditRecord.ACTION_NO_SHOW);
        entity.setOccurredAt(OffsetDateTime.parse("2026-09-27T09:30:00+08:00"));
        entity.setWindowDays(90);
        entity.setRestrictFrom(LocalDate.of(2026, 9, 27));
        entity.setRestrictTo(LocalDate.of(2026, 12, 26));
        entity.setReleaseReason("管理端手工解除");

        ApptCreditVO vo = AppointmentConverter.INSTANCE.toCreditVO(entity);

        assertThat(vo.id()).isEqualTo(5001L);
        assertThat(vo.patientId()).isEqualTo(77L);
        assertThat(vo.action()).isEqualTo("NO_SHOW");
        assertThat(vo.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-09-27T09:30:00+08:00"));
        assertThat(vo.windowDays()).isEqualTo(90);
        assertThat(vo.restrictFrom()).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(vo.restrictTo()).isEqualTo(LocalDate.of(2026, 12, 26));
        assertThat(vo.releaseReason()).isEqualTo("管理端手工解除");
    }

    @Test
    @DisplayName("爽约信用 null 传播：未达阈值无限约区间且未解除时区间与留痕均为 null")
    void creditPropagatesNullables() {
        ApptCreditRecord entity = new ApptCreditRecord();
        entity.setId(5002L);
        entity.setPatientId(78L);
        entity.setAction(ApptCreditRecord.ACTION_TIMEOUT_CANCEL);
        entity.setOccurredAt(OffsetDateTime.parse("2026-09-27T08:00:00+08:00"));
        entity.setWindowDays(90);

        ApptCreditVO vo = AppointmentConverter.INSTANCE.toCreditVO(entity);

        // 未达限约阈值/未解除：restrictFrom/restrictTo/releaseReason 保持 null（列表筛选按区间判定的依据）
        assertThat(vo.restrictFrom()).isNull();
        assertThat(vo.restrictTo()).isNull();
        assertThat(vo.releaseReason()).isNull();
    }

    @Test
    @DisplayName("就诊记录直映：11 字段全量等价（含复诊标记与急诊分级）")
    void visitMapsAllFields() {
        Visit entity = new Visit();
        entity.setId(6001L);
        entity.setVisitId("O2026092800002");
        entity.setPatientId(77L);
        entity.setApptId(9001L);
        entity.setDeptCode("D001");
        entity.setDoctorId("doc-0042");
        entity.setVisitType(VisitType.SPECIAL);
        entity.setIsRevisit((short) 1);
        entity.setTriageLevel(2);
        entity.setStatus(VisitStatus.WAITING);
        entity.setRegisteredAt(OffsetDateTime.parse("2026-09-28T09:05:00+08:00"));

        VisitVO vo = AppointmentConverter.INSTANCE.toVisitVO(entity);

        assertThat(vo.id()).isEqualTo(6001L);
        assertThat(vo.visitId()).isEqualTo("O2026092800002");
        assertThat(vo.patientId()).isEqualTo(77L);
        assertThat(vo.apptId()).isEqualTo(9001L);
        assertThat(vo.deptCode()).isEqualTo("D001");
        assertThat(vo.doctorId()).isEqualTo("doc-0042");
        assertThat(vo.visitType()).isEqualTo(VisitType.SPECIAL);
        assertThat(vo.isRevisit()).isEqualTo((short) 1);
        assertThat(vo.triageLevel()).isEqualTo(2);
        assertThat(vo.status()).isEqualTo(VisitStatus.WAITING);
        assertThat(vo.registeredAt()).isEqualTo(OffsetDateTime.parse("2026-09-28T09:05:00+08:00"));
    }

    @Test
    @DisplayName("就诊记录 null 传播：挂号链路医生未排班/未分诊时 doctorId 与 triageLevel 为 null")
    void visitPropagatesNullables() {
        Visit entity = new Visit();
        entity.setId(6002L);
        entity.setVisitId("O2026092800003");
        entity.setPatientId(78L);
        entity.setDeptCode("D002");
        entity.setVisitType(VisitType.GENERAL);
        entity.setIsRevisit((short) 0);
        entity.setStatus(VisitStatus.REGISTERED);
        entity.setRegisteredAt(OffsetDateTime.parse("2026-09-28T10:05:00+08:00"));

        VisitVO vo = AppointmentConverter.INSTANCE.toVisitVO(entity);

        // 挂号链路未分诊：triageLevel 由分诊台写入前保持 null；apptId 停诊兜底场景亦可空
        assertThat(vo.apptId()).isNull();
        assertThat(vo.doctorId()).isNull();
        assertThat(vo.triageLevel()).isNull();
    }
}
