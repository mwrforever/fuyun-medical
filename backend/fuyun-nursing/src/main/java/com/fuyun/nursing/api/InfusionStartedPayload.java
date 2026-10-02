package com.fuyun.nursing.api;

import java.time.Instant;

/**
 * 开始输注事件载荷（nursing.infusion.started，V800 id 62 冻结契约，Task 6 发布）：
 * 输液执行单开始输注时发布，M14 订阅建立告警↔任务↔传感器关联（FU-M05-06）。
 *
 * @param executionNo  执行单业务号，非空；来源：输液类执行单（M14 关联锚）
 * @param patientId    患者主索引，非空；来源：执行单所属就诊关联
 * @param visitId      就诊标识（住院就诊号），非空
 * @param bagLabelCode 输液袋标签码，非空；来源：摆药贴签（M06 签收链）或护士站补录
 * @param startedAt    开始输注时点（UTC），非空；来源：开始输注操作落库时点
 */
public record InfusionStartedPayload(
        String executionNo, long patientId, String visitId, String bagLabelCode, Instant startedAt) {}
