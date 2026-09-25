package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 转科入参（POST /api/v1/inpatient/visits/{visitId}/transfer）——转科四阶段编排执行体
 * （04-inpatient Spec §3.5）：跨病区护理单元变更，单事务 = 转出病区长期医嘱自动停嘱 →
 * 在途三分（计划面归 Task 7/8 计划服务补挂转科钩子）→ 床位流转（转出床消毒流转/目标床
 * 占床/visit 定位原子更新）→ 发布 inpatient.visit.transferred。目标病区不得与当前病区
 * 相同（同病区床位切换走 change-bed 轻量路径）。
 *
 * @param toDeptId 目标科室编码，可空（缺席保留原科室；病区归属科室由 M01 维护）；来源：医生站转科单
 * @param toWardId 目标病区编码，必填；来源：医生站转科单（目标床位归属病区）
 * @param toBedId  目标床位 id，必填；来源：目标病区签床预占（RESERVED/FREE 均可占）
 */
public record TransferRequest(
        String toDeptId,
        @NotBlank(message = "toWardId 不能为空") String toWardId,
        @NotNull(message = "toBedId 不能为空") Long toBedId) {}
