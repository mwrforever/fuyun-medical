package com.fuyun.outpatient.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.outpatient.entity.Schedule;
import com.fuyun.outpatient.entity.ScheduleTemplate;
import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.ScheduleStatus;
import com.fuyun.outpatient.enums.SessionType;
import com.fuyun.outpatient.vo.ScheduleTemplateVO;
import com.fuyun.outpatient.vo.ScheduleVO;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 排班域转换器单测（BUG-22 迁入守护）：模板/日历实体→出参直映与原 ScheduleServiceImpl 手写逐
 * 字段等价——全字段断言锁组件名对位语义（record 位置参数增删/调序在此即失败，模板字段演进如加
 * 停诊标记时出参漏映射即被拦截），并守护可空字段的 null 传播（原手写直传 null 语义）。
 */
class ScheduleConverterTest {

    @Test
    @DisplayName("排班模板直映：15 字段全量等价（含失效日/位串/放号时点原样承载）")
    void templateMapsAllFields() {
        ScheduleTemplate entity = new ScheduleTemplate();
        entity.setId(4001L);
        entity.setDeptCode("D001");
        entity.setDoctorId("doc-0042");
        entity.setEffFrom(LocalDate.of(2026, 10, 1));
        entity.setEffTo(LocalDate.of(2026, 12, 31));
        entity.setWeekPattern("1100000");
        entity.setSession(SessionType.MORNING);
        entity.setApptType(ApptType.EXPERT);
        entity.setSlotStart(LocalTime.of(8, 0));
        entity.setSlotEnd(LocalTime.of(12, 0));
        entity.setSlotQuota(20);
        entity.setRoom("301 诊室");
        entity.setReleaseDays(7);
        entity.setReleaseTime(LocalTime.of(7, 0));
        entity.setStatus("ACTIVE");

        ScheduleTemplateVO vo = ScheduleConverter.INSTANCE.toTemplateVO(entity);

        assertThat(vo.id()).isEqualTo(4001L);
        assertThat(vo.deptCode()).isEqualTo("D001");
        assertThat(vo.doctorId()).isEqualTo("doc-0042");
        assertThat(vo.effFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(vo.effTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(vo.weekPattern()).isEqualTo("1100000");
        assertThat(vo.session()).isEqualTo(SessionType.MORNING);
        assertThat(vo.apptType()).isEqualTo(ApptType.EXPERT);
        assertThat(vo.slotStart()).isEqualTo(LocalTime.of(8, 0));
        assertThat(vo.slotEnd()).isEqualTo(LocalTime.of(12, 0));
        assertThat(vo.slotQuota()).isEqualTo(20);
        assertThat(vo.room()).isEqualTo("301 诊室");
        assertThat(vo.releaseDays()).isEqualTo(7);
        assertThat(vo.releaseTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(vo.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("排班模板 null 传播：长期有效无失效日、未配置诊疗室与放号时点均直传 null")
    void templatePropagatesNullables() {
        ScheduleTemplate entity = new ScheduleTemplate();
        entity.setId(4002L);
        entity.setDeptCode("D002");
        entity.setDoctorId("doc-0043");
        entity.setEffFrom(LocalDate.of(2026, 10, 1));
        entity.setWeekPattern("0000010");
        entity.setSession(SessionType.AFTERNOON);
        entity.setApptType(ApptType.GENERAL);
        entity.setSlotStart(LocalTime.of(14, 0));
        entity.setSlotEnd(LocalTime.of(17, 30));
        entity.setSlotQuota(15);
        entity.setReleaseDays(7);
        entity.setStatus("ACTIVE");

        ScheduleTemplateVO vo = ScheduleConverter.INSTANCE.toTemplateVO(entity);

        // 原手写直传语义：effTo null=长期有效、room 未配置、releaseTime null=库默认 07:00 口径——不得转默认值
        assertThat(vo.effTo()).isNull();
        assertThat(vo.room()).isNull();
        assertThat(vo.releaseTime()).isNull();
        assertThat(vo.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("排班日历直映：12 字段全量等价（含停诊态停诊原因承载）")
    void scheduleMapsAllFields() {
        Schedule entity = new Schedule();
        entity.setId(4101L);
        entity.setTemplateId(4001L);
        entity.setSchedDate(LocalDate.of(2026, 10, 8));
        entity.setSession(SessionType.MORNING);
        entity.setDeptCode("D001");
        entity.setDoctorId("doc-0042");
        entity.setApptType(ApptType.EXPERT);
        entity.setTotalQuota(20);
        entity.setUsedQuota(13);
        entity.setRoom("301 诊室");
        entity.setStatus(ScheduleStatus.STOPPED);
        entity.setStopReason("医生临时出差");

        ScheduleVO vo = ScheduleConverter.INSTANCE.toScheduleVO(entity);

        assertThat(vo.id()).isEqualTo(4101L);
        assertThat(vo.templateId()).isEqualTo(4001L);
        assertThat(vo.schedDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(vo.session()).isEqualTo(SessionType.MORNING);
        assertThat(vo.deptCode()).isEqualTo("D001");
        assertThat(vo.doctorId()).isEqualTo("doc-0042");
        assertThat(vo.apptType()).isEqualTo(ApptType.EXPERT);
        assertThat(vo.totalQuota()).isEqualTo(20);
        assertThat(vo.usedQuota()).isEqualTo(13);
        assertThat(vo.room()).isEqualTo("301 诊室");
        assertThat(vo.status()).isEqualTo(ScheduleStatus.STOPPED);
        assertThat(vo.stopReason()).isEqualTo("医生临时出差");
    }

    @Test
    @DisplayName("排班日历 null 传播：正常排班未配置诊疗室且无停诊原因时两字段均 null")
    void schedulePropagatesNullables() {
        Schedule entity = new Schedule();
        entity.setId(4102L);
        entity.setTemplateId(4002L);
        entity.setSchedDate(LocalDate.of(2026, 10, 9));
        entity.setSession(SessionType.AFTERNOON);
        entity.setDeptCode("D002");
        entity.setDoctorId("doc-0043");
        entity.setApptType(ApptType.GENERAL);
        entity.setTotalQuota(15);
        entity.setUsedQuota(0);
        entity.setStatus(ScheduleStatus.NORMAL);

        ScheduleVO vo = ScheduleConverter.INSTANCE.toScheduleVO(entity);

        // 原手写直传语义：正常态无停诊原因、未配置诊疗室——null 原样传播不得转默认值
        assertThat(vo.room()).isNull();
        assertThat(vo.stopReason()).isNull();
        assertThat(vo.status()).isEqualTo(ScheduleStatus.NORMAL);
    }
}
