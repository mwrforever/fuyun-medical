package com.fuyun.nursing.api;

import java.time.Instant;

/**
 * 执行单执行回执事件载荷（nursing.order-execution.completed，V800 id 64 冻结契约，Task 5 发布）：
 * 执行单终态（完成/取消）时发布，M04 订阅与 execute-confirm 主路径双路对账（CF-6 辅路径）。
 * desc 中「环节时点集」即本 record 四时点组件的冻结概括（V800 文本不可改，组件展开面在此冻结）。
 *
 * @param executionNo 执行单业务号，非空；来源：执行域发号器（M04 对账幂等锚之一）
 * @param m04PlanNo   M04 医嘱计划号，非空；来源：生成执行单时的计划引用（长期医嘱计划拆分单）
 * @param m04OrderNo  M04 医嘱号，非空；来源：生成执行单时的医嘱引用
 * @param patientId   患者主索引，非空；来源：执行单所属就诊关联
 * @param visitId     就诊标识（住院就诊号），非空
 * @param signedAt    签收时点（UTC），非空；环节时点集之一
 * @param checkedAt   核对通过时点（UTC），非空；环节时点集之一（扫码核对 PASS 时点）
 * @param startedAt   开始执行时点（UTC），非空；环节时点集之一（输注类=开始输注时点）
 * @param finishedAt  执行完成时点（UTC），非空；环节时点集之一（输注类=拔针时点）
 * @param executorId  执行护士员工 ID，非空；来源：执行操作主体（M01 用户标识）
 * @param overrideFlag 是否破码放行，true=经授权破码跳过常规核对；来源：执行单 override_flag
 */
public record OrderExecutionCompletedPayload(
        String executionNo,
        String m04PlanNo,
        String m04OrderNo,
        long patientId,
        String visitId,
        Instant signedAt,
        Instant checkedAt,
        Instant startedAt,
        Instant finishedAt,
        long executorId,
        boolean overrideFlag) {}
