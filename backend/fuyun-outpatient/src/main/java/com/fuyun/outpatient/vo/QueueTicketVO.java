package com.fuyun.outpatient.vo;

import com.fuyun.outpatient.enums.TicketStatus;
import com.fuyun.outpatient.enums.TicketType;
import java.time.OffsetDateTime;

/**
 * 候诊票据出参（队列快照 GET /queues/{queueId}/tickets 与报到/调级/叫号/过号/重呼直出形态）：
 * 脱敏出网口径——patientName 为 patient 侧掩码收口后的展示名（保留姓氏），本 VO 无证件号/联系
 * 方式等敏感字段（03 Spec §9 脱敏红线）；visitId 为 O 型业务号（分诊台操作锚，非敏感字段）。
 *
 * @param id            票据主键（过号/重呼端点定位锚）
 * @param visitId       就诊号（O+yyyyMMdd+5 位流水）
 * @param queueId       队列标识（=dept_code 诊区队列）
 * @param ticketNo      票号（队列内当日序号，如 A007）
 * @param ticketType    票别（FIRST/VISIT/RETURN/EXTRA）
 * @param doctorId      指派医生 id（未指派为 null）
 * @param priorityScore 优先级分（冻结公式 0~999）
 * @param queueSeq      队列当日序
 * @param queueTime     建行时间（同分排序权威，库端时间戳）
 * @param calledCount   叫号次数
 * @param callTime      最近叫号时间（未叫为 null）
 * @param status        票据状态（WAITING/CALLED/SERVING/SERVED/PASSED/CANCELLED）
 * @param patientName   脱敏展示名（patient 侧掩码收口，如 张*；无命中为 null）
 */
public record QueueTicketVO(
        Long id,
        String visitId,
        String queueId,
        String ticketNo,
        TicketType ticketType,
        String doctorId,
        Integer priorityScore,
        Integer queueSeq,
        OffsetDateTime queueTime,
        Integer calledCount,
        OffsetDateTime callTime,
        TicketStatus status,
        String patientName) {}
