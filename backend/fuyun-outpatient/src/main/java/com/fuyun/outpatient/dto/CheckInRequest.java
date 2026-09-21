package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 分诊报到请求（POST /triage/check-in，M03 分诊台/自助签到统一入口）：visit 须已 REGISTERED
 * （预约已 TAKEN 直接可报到——TAKEN 即 visit 已签发，天然满足）。老幼残因子为分诊台人工判定
 * 录入（老幼残 200 跨类叠加分依据，词表 ELDERLY/CHILD/DISABLED；服务层词表校验词表外 OP-1019）。
 *
 * @param visitId         就诊号（O+yyyyMMdd+5 位流水），非空白；来源：分诊台扫码/输入
 * @param stationId       分诊台/自助终端标识，非空白；来源：报到发起端配置
 * @param priorityFactors 老幼残优先级因子，可空（无因子=基础/类别分口径）；来源：分诊台人工判定
 */
public record CheckInRequest(
        @NotBlank(message = "visitId 不得为空白") String visitId,
        @NotBlank(message = "stationId 不得为空白") String stationId,
        List<String> priorityFactors) {}
