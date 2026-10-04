package com.fuyun.nursing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 输液拔针入参（POST /api/v1/nursing/executions/{no}/needle-out）：EXECUTING 输液执行单的
 * 完成形态（INFUSION 型 finish 由本端点承接）——腕带核对 + 实际输注量确认 + 监测挂接收口
 * + 自动入量 + 双路回签。actualVolumeMl 业务边界 0~5000（服务层显式守卫 NS-1019，
 * Bean Validation 双保险——W-22⑦ 禁裸值口径）。
 *
 * @param executorId     拔针护士员工 ID（兼容保留——服务端一律以令牌身份落值，W-72，
 *                       2026-10-03 裁决；本字段不再消费），可空；来源：PDA 当前登录护士
 * @param actualVolumeMl 实际输注量 ml（必填 0~5000——护士确认值，自动入量行数量来源），非空；
 *                       来源：拔针确认表单
 * @param wristbandCode  患者腕带码（必填 ≤64——三向核对同款语义：腕带维=visitId 匹配），非空；
 *                       来源：PDA 腕带扫码
 */
public record NeedleOutRequest(
        Long executorId,

        @NotNull(message = "实际输注量必填（actualVolumeMl）")
        @Min(value = 0, message = "实际输注量下界 0 ml")
        @Max(value = 5000, message = "实际输注量上界 5000 ml")
        Integer actualVolumeMl,

        @NotBlank(message = "患者腕带码必填（wristbandCode）") @Size(max = 64, message = "患者腕带码超长（≤64）")
        String wristbandCode) {}
