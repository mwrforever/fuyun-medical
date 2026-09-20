package com.fuyun.outpatient.vo;

/**
 * 叫号 WS 推送载荷（/topic/outpatient/queue/{deptCode} 与 /topic/outpatient/doctor/{doctorId}
 * 双通道，队列快照 REST 之外的实时通道，端到端 ≤2s——03 Spec :198）：脱敏出网口径——以 ticketNo+
 * 姓名脱敏表达患者标识，不带 visitId/patientId 原始标识（大屏/医生站公开面最小暴露，brief 冻结
 * 载荷口径）。
 *
 * @param type        消息类型（P1 恒 CALLED 叫号；后续类型随通道演进登记）
 * @param ticketNo    票号（队列内当日序号，如 A007）
 * @param patientName 脱敏展示名（patient 侧掩码收口，如 张*；解析失败为 null）
 * @param doctorId    叫号医生 id
 * @param room        诊室（当日排班 room，缺排班/未配置为 null）
 */
public record QueueCalledNotice(String type, String ticketNo, String patientName, String doctorId, String room) {}
