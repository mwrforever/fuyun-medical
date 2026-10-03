package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 执行单开始执行入参（POST /api/v1/nursing/executions/{no}/start）：CHECKED→EXECUTING；
 * 计划时间窗外（±ward_config.execute_time_window_minutes）且未破码放行时 NS-1027 拒绝。
 * INFUSION 型建/激活监测挂接与 infusion.started 事件归 Task 6 在 CAS 成功后段扩展。
 *
 * @param executorId        执行护士员工 ID（必填，落 executor_id），非空；来源：PDA 当前登录护士
 * @param deviceId          输液泵设备标识（≤64，可空——Task 6 INFUSION 分支回填监测挂接
 *                          iot_device_id；GENERIC 路径仅日志留痕），可空；来源：PDA 设备扫码
 * @param overrideTimeWindow 窗外执行确认（可空=true 时跳过时间窗校验——现场口头医嘱场景，
 *                          与破码放行 override_flag 三源合流），可空；来源：PDA 确认弹窗
 */
public record StartRequest(
        @NotNull(message = "执行护士员工ID必填（executorId）") Long executorId,
        @Size(max = 64, message = "设备标识超长（≤64）") String deviceId,
        Boolean overrideTimeWindow) {}
