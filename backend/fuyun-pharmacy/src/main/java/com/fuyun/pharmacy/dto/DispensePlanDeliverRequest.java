package com.fuyun.pharmacy.dto;

import jakarta.validation.constraints.Size;

/**
 * 摆药计划配送交接入参（POST /api/v1/pharmacy/dispense-plans/{no}/deliver，P2 PR-3 Task 8）。
 * deliver 为 CHECKED 态内配送交接时间线半步（issued_at 置位不迁状态）；carrier 无落列载体
 * （V1110 无配送列、零迁移授权），以日志留痕承载——报告 concerns 申报注记。
 *
 * @param carrier 配送人/载体说明，可空 ≤64；来源：药房配送交接登记
 */
public record DispensePlanDeliverRequest(@Size(max = 64) String carrier) {}
