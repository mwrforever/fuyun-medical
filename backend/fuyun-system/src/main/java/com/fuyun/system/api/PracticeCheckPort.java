package com.fuyun.system.api;

/**
 * 执业授权校验端口（M01 执业授权域对消费模块的唯一进程内出口，api 包契约；CF-2 REST 契约
 * POST /api/v1/system/practice/check 的 api 面镜像——跨模块进程内调用仅经 api 包合法，B.2）。
 * 消费方：M03 门诊开单执业授权强校验（PRESCRIPTION 处方权，Spec :140）等。
 *
 * <p>授权类型词表（全仓消费方逐字引用）：PRESCRIPTION/NARCOTIC/ANTIBIO_NONRESTRICT/
 * ANTIBIO_RESTRICT/ANTIBIO_SPECIAL。真实校验经 V704 practice_grant 表 EFFECTIVE 授权+有效期判定。
 */
public interface PracticeCheckPort {

    /**
     * 执行授权校验：EFFECTIVE 且有效期含校验日判通过（服务端当前时刻口径）。
     *
     * @param employeeId 员工 ID（sys_employee.id），非空；来源：运行态 userId 直作 employeeId
     *                   （Task 2 身份链对齐口径，V704 种子同刻度）
     * @param grantType  授权类型词表值，非空；来源：业务场景常量（如开单=PRESCRIPTION）
     * @return 校验结果（passed+reason 原样透传，不二次包装）；passed=false 的两态 reason 文案
     *         （授权已过期/无有效记录）由消费方拼入业务异常 message
     */
    PracticeCheckResult check(long employeeId, String grantType);
}
