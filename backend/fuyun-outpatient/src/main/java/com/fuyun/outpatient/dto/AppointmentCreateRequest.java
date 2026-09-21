package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 统一预约/当日挂号请求（POST /api/v1/outpatient/appointments，M03 FU-M03-02/03 多渠道统一入口）：
 * channel 语义分流——WINDOW/KIOSK 当日挂号一步 TAKEN（同事务签发 visit），PORTAL 预约占位 RESERVED
 * （pay_deadline=now()+支付时限）；MINIAPP/CONSULT/EXTERNAL 词表预留位（服务端显式拒绝 OP-1019）。
 *
 * @param patientId 患者主索引，非空；来源：工作站表单（portal 匿名通道经介质解析服务端换得，不透传）
 * @param poolId    号源池行 id，非空；来源：可约号源查询（GET /number-pools/available）选中行
 * @param channel   预约渠道 code（ApptChannel 词表），非空；来源：渠道入口标识
 */
public record AppointmentCreateRequest(
        @NotNull(message = "patientId 不能为空") Long patientId,
        @NotNull(message = "poolId 不能为空") Long poolId,
        @NotBlank(message = "channel 不能为空") String channel) {}
