package com.fuyun.outpatient.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.outpatient.entity.QueueTicket;
import com.fuyun.outpatient.enums.TicketStatus;
import com.fuyun.outpatient.enums.TicketType;
import com.fuyun.outpatient.vo.QueueTicketVO;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 候诊票据域转换器单测（BUG-20 迁入守护）：票据实体→出参直映与原 TriageServiceImpl 手写逐字段
 * 等价——全字段断言锁组件名对位语义（record 位置参数增删/调序在此即失败），并守护跨源透传字段
 * （triageLevel/patientName）与可空字段的 null 传播（原手写直传 null 语义）。
 */
class QueueTicketConverterTest {

    @Test
    @DisplayName("候诊票据直映：11 票面字段全量等价 + 跨源 triageLevel/patientName 透传")
    void ticketMapsAllFields() {
        QueueTicket entity = new QueueTicket();
        entity.setId(7001L);
        entity.setVisitId("O2026092800004");
        entity.setQueueId("D001");
        entity.setTicketNo("A007");
        entity.setTicketType(TicketType.RETURN);
        entity.setDoctorId("doc-0042");
        entity.setPriorityScore(400);
        entity.setQueueSeq(7);
        entity.setQueueTime(OffsetDateTime.parse("2026-09-28T08:30:00+08:00"));
        entity.setCalledCount(2);
        entity.setCallTime(OffsetDateTime.parse("2026-09-28T09:10:00+08:00"));
        entity.setStatus(TicketStatus.CALLED);

        QueueTicketVO vo = QueueTicketConverter.INSTANCE.toQueueTicketVO(entity, 2, "张*");

        assertThat(vo.id()).isEqualTo(7001L);
        assertThat(vo.visitId()).isEqualTo("O2026092800004");
        assertThat(vo.queueId()).isEqualTo("D001");
        assertThat(vo.ticketNo()).isEqualTo("A007");
        assertThat(vo.ticketType()).isEqualTo(TicketType.RETURN);
        assertThat(vo.doctorId()).isEqualTo("doc-0042");
        assertThat(vo.priorityScore()).isEqualTo(400);
        assertThat(vo.queueSeq()).isEqualTo(7);
        assertThat(vo.queueTime()).isEqualTo(OffsetDateTime.parse("2026-09-28T08:30:00+08:00"));
        assertThat(vo.calledCount()).isEqualTo(2);
        assertThat(vo.callTime()).isEqualTo(OffsetDateTime.parse("2026-09-28T09:10:00+08:00"));
        assertThat(vo.status()).isEqualTo(TicketStatus.CALLED);
        assertThat(vo.patientName()).isEqualTo("张*");
        assertThat(vo.triageLevel()).isEqualTo(2);
    }

    @Test
    @DisplayName("候诊票据 null 传播：未指派/未叫号票面可空字段与未分级/无命中跨源字段均直传 null")
    void ticketPropagatesNullables() {
        QueueTicket entity = new QueueTicket();
        entity.setId(7002L);
        entity.setVisitId("O2026092800005");
        entity.setQueueId("D002");
        entity.setTicketNo("B012");
        entity.setTicketType(TicketType.FIRST);
        entity.setPriorityScore(300);
        entity.setQueueSeq(12);
        entity.setQueueTime(OffsetDateTime.parse("2026-09-28T08:35:00+08:00"));
        entity.setCalledCount(0);
        entity.setStatus(TicketStatus.WAITING);

        QueueTicketVO vo = QueueTicketConverter.INSTANCE.toQueueTicketVO(entity, null, null);

        // 原手写直传语义：二次分诊未定医生、首次未叫号、未分级、患者名无命中——null 原样传播不得转默认值
        assertThat(vo.doctorId()).isNull();
        assertThat(vo.callTime()).isNull();
        assertThat(vo.triageLevel()).isNull();
        assertThat(vo.patientName()).isNull();
        assertThat(vo.status()).isEqualTo(TicketStatus.WAITING);
    }
}
