package com.fuyun.outpatient.api;

import java.util.List;

/**
 * 预约支付超时回调事件载荷（outpatient.appointment.timeout，V204 id 39 冻结契约）：
 * fy.delay 档位 appointment-timeout 到期经 DLX 以本路由键回 fy.topic 的自产自消内部事件，
 * outpatient 自消费置 NO_SHOW+号源回池+信用记录（超时与支付成功并发以预约单状态 CAS 先到先得）。
 *
 * @param apptNo    预约单业务号（超时判定与状态 CAS 锚点）
 * @param patientId 患者主索引（信用记录归属）
 * @param poolId    号源池行 id（号源回池定位）
 */
public record AppointmentTimeoutPayload(String apptNo, Long patientId, Long poolId) {

    /** 顶层组件名清单（契约测试与 V204 id 39 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES = List.of("apptNo", "patientId", "poolId");
}
