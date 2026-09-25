package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 嘱托按需触发入参（POST /api/v1/inpatient/order-plans/standby-trigger，FU-M04-06）：
 * 长期备用嘱（standby_flag=true）按需触发单次执行计划——多次触发多次台账（每次触发独立
 * 计划实例，plan_no 各异），不重复计价由 M13 唯一键兜底。
 *
 * @param orderNo 嘱托医嘱号（standby_flag=true 的长期医嘱），非空；来源：护士站嘱托医嘱卡触发按钮
 */
public record StandbyTriggerRequest(
        @NotBlank @Size(max = 32) String orderNo) {}
