package com.fuyun.outpatient.vo;

import com.fuyun.outpatient.enums.TicketStatus;
import com.fuyun.outpatient.enums.TicketType;
import java.time.OffsetDateTime;

/**
 * 医生站候诊列表行出参（GET /doctor/patient-queue 直出）：本队列 WAITING/CALLED 票+患者摘要
 * （patientName 为 patient 侧掩码收口展示名——Spec §9 脱敏红线，原文不出 patient 模块）+过敏
 * 标识位（P1 恒 false——M02 过敏订阅缓存随 P-later 接入，声明位先挂）。
 *
 * @param ticketId      票据主键；来源：queue_ticket 落库回填
 * @param visitId       就诊号（CF-3 冻结）
 * @param ticketNo      票号（队列内当日序，A+%03d）
 * @param patientName   患者脱敏展示名（patient 侧掩码收口），可空（无命中）
 * @param ticketType    票别 code（FIRST/RETURN 等）
 * @param priorityScore 优先级分（冻结公式 0~999）
 * @param status        票据状态 code（候诊列表词表 WAITING/CALLED）
 * @param allergyFlag   过敏标识位（P1 恒 false——M02 订阅缓存 P-later 注记）
 * @param queueTime     建行时间（同分排序权威，库端时间戳）
 * @param calledCount   叫号次数（候中票 ≥1，候诊票 0）
 */
public record DoctorQueueItemVO(
        Long ticketId,
        String visitId,
        String ticketNo,
        String patientName,
        TicketType ticketType,
        Integer priorityScore,
        TicketStatus status,
        boolean allergyFlag,
        OffsetDateTime queueTime,
        Integer calledCount) {}
