package com.fuyun.patient.service.impl;

import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientIdentityQuery;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.service.IPatientIdentifierService;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标识介质解析实现（patient/api PatientIdentityQuery，PR-5 Task 5 portal 匿名通道消费面）：
 * 转调 {@link IPatientIdentifierService#resolveActive}（ACTIVE 等值查，PAT-1001 拒绝语义原样透出）
 * + {@link PatientContextResolver} 主档归一（从档持卡收敛主档，M02 红线 1）。
 * 装配归 PatientWebConfig @Import；线程安全：无状态单例。
 */
public class PatientIdentityQueryImpl implements PatientIdentityQuery {

    private final IPatientIdentifierService identifierService;

    private final PatientContextResolver patientContextResolver;

    /**
     * 全参构造器（装配归 PatientWebConfig @Import）。
     *
     * @param identifierService      标识服务，非空；ACTIVE 盲索引等值查
     * @param patientContextResolver 患者上下文解析，非空；主档归一
     */
    public PatientIdentityQueryImpl(
            IPatientIdentifierService identifierService, PatientContextResolver patientContextResolver) {
        this.identifierService = identifierService;
        this.patientContextResolver = patientContextResolver;
    }

    /**
     * 按标识介质解析患者主索引（从档收敛主档）。
     *
     * @param identifierType  标识类型词表值，非空
     * @param identifierValue 标识值明文，非空（仅本方法生命周期内存活）
     * @return 归一后主档患者主索引
     * @throws com.fuyun.common.exception.BizException PAT-1001（404）标识未登记或已失效时触发
     */
    @Override
    @Transactional(readOnly = true)
    public long resolveActivePatientId(String identifierType, String identifierValue) {
        PatientIdentifier identifier = identifierService.resolveActive(identifierType, identifierValue);
        // 从档持卡解析收敛主档（与 PatientContextResolver.resolve 同一口径，M02 红线 1）
        return patientContextResolver.resolve(identifier.getPatientId()).resolvedPatientId();
    }
}
