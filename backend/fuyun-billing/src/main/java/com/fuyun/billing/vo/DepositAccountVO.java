package com.fuyun.billing.vo;

import com.fuyun.billing.entity.DepositAccount;

/**
 * 押金账户出参（GET /deposits?visitId= 出网载体；组件清单为 Task 18 IT 与 Task 19 前端唯一依据，
 * 禁改名改序）：枚举出 code 字符串、金额/id 一律 Long 包装出网（统一契约口径），经 Jackson→string。
 *
 * @param id               雪花主键
 * @param patientId        患者主索引
 * @param visitId          CF-3 住院就诊号（一就诊一账户）
 * @param balance          余额（分；mutateBalance 原子回读口径）
 * @param warningThreshold 欠费预警阈值（分）
 * @param status           账户状态 NORMAL/ARREARS/SETTLED/CLOSED（code 字符串）
 */
public record DepositAccountVO(
        Long id, Long patientId, String visitId, Long balance, Long warningThreshold, String status) {

    /**
     * 实体 → 出参静态工厂（controller 出网边界专用，禁实体直出；金额为关键业务字段禁 MapStruct 手写映射）。
     *
     * @param account 押金账户实体，非空；来源：service 事务内查询结果
     * @return 出参 VO，非空；状态枚举转 code 字符串，金额列原样透传
     */
    public static DepositAccountVO from(DepositAccount account) {
        return new DepositAccountVO(
                account.getId(),
                account.getPatientId(),
                account.getVisitId(),
                account.getBalance(),
                account.getWarningThreshold(),
                account.getStatus() == null ? null : account.getStatus().getCode());
    }
}
