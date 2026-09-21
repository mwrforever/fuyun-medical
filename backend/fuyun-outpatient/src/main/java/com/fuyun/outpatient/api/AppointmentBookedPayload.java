package com.fuyun.outpatient.api;

import java.util.List;

/**
 * 预约成功事件载荷（outpatient.appointment.booked，V204 id 36 冻结契约）：
 * 预约落库同事务发布，M18 患者端订单同步与 M19 统计依据（订阅随 P2）。
 *
 * @param apptNo    预约单业务号（雪花外业务号，string 承载）
 * @param patientId 患者主索引
 * @param schedDate 排班日期（yyyyMMdd，string 承载）
 * @param session   诊次（上午/下午/晚间业务代码）
 * @param deptCode  开诊科室编码
 * @param apptType  预约类型（普通/专家等业务代码）
 * @param channel   预约渠道（窗口/自助机/公众号/小程序/诊间/外联；P1 实装窗口/portal 两渠道）
 */
public record AppointmentBookedPayload(
        String apptNo,
        Long patientId,
        String schedDate,
        String session,
        String deptCode,
        String apptType,
        String channel) {

    /** 顶层组件名清单（契约测试与 V204 id 36 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES =
            List.of("apptNo", "patientId", "schedDate", "session", "deptCode", "apptType", "channel");
}
