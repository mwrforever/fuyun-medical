package com.fuyun.patient.api;

/**
 * 标识介质解析契约（M02 Spec §8「被下游依赖：M03 身份解析」面；PR-5 Task 5 portal 匿名通道
 * 裁决 13 首个消费方，PracticeCheckPort 同款「api 接口+provider 侧实现转调」范式增量）：
 * 就诊卡号/证件号明文 → 归一主档 patient_id。实现侧以盲索引等值查定位（明文不作查询条件不入日志），
 * 无 ACTIVE 命中按未解析拒绝。
 */
public interface PatientIdentityQuery {

    /**
     * 按标识介质解析患者主索引（从档持卡解析收敛主档，与 {@link PatientContextResolver} 同一口径）。
     *
     * @param identifierType  标识类型词表值（ID_CARD 证件号 / VISIT_CARD 就诊卡号），非空；来源：调用方入参
     * @param identifierValue 标识值明文，非空；来源：调用方入参（仅本方法生命周期内存活，禁入日志）
     * @return 归一后主档患者主索引（resolvedPatientId）
     * @throws com.fuyun.common.exception.BizException PAT-1001（404）标识未登记或已失效（挂失/解绑/
     *                                                 替换即解析失效语义）时触发；建议处理策略：
     *                                                 调用方按业务失败终止并提示
     */
    long resolveActivePatientId(String identifierType, String identifierValue);
}
