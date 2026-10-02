/**
 * M04 住院域对外契约出口（宪法 B.1 api 包）：事件载荷 record 与跨模块进程内端口。
 * NamedInterface 将本包对其他模块显式可见，api 之外的一切包保持模块私有。
 *
 * <p>P2 PR-3 Task 5 起新增 {@link com.fuyun.inpatient.api.OrderExecutionConfirmPort}（M05
 * 执行回签端口——nursing 执行单完成主路径进程内直调，billing api 端口同款先例）；回签
 * 出入参契约（ExecuteConfirmRequest/ExecuteConfirmVO）自 dto/vo 迁入本包随端口冻结，
 * 模块内既有 REST 面经 import 调整零行为变化。契约变更属双向评审事项（消费方 M05）。
 */
@NamedInterface("api")
package com.fuyun.inpatient.api;

import org.springframework.modulith.NamedInterface;
