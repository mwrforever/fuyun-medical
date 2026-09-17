package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.patient.entity.PrivacyAuth;
import com.fuyun.patient.enums.PrivacyAuthStatus;
import com.fuyun.patient.mapper.PrivacyAuthMapper;
import com.fuyun.patient.service.IPrivacyAuthService;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * 隐私授权实现（patient.privacy_auth 主表）：知情同意登记（Task 5）+ 按患者清单查询 + 派生状态
 * （Task 12）。EXPIRED 为读侧派生态：valid_to 相对当前时刻判定，零定时任务设计（库值恒存
 * EFFECTIVE，不回写）；REVOKED 为落库终态，派生时原样透出不复活。
 */
public class PrivacyAuthServiceImpl extends ServiceImpl<PrivacyAuthMapper, PrivacyAuth> implements IPrivacyAuthService {

    /**
     * 登记知情同意授权。
     *
     * @param patientId    患者主索引，非空
     * @param authBasisRef 授权依据引用，非空
     * @return 授权行 id
     */
    @Override
    public Long recordInformedConsent(long patientId, String authBasisRef) {
        PrivacyAuth auth = new PrivacyAuth();
        auth.setPatientId(patientId);
        auth.setAuthType("INFORMED_CONSENT");
        auth.setAuthBasis(authBasisRef);
        auth.setSignedAt(OffsetDateTime.now());
        auth.setStatus("EFFECTIVE");
        save(auth);
        return auth.getId();
    }

    /**
     * 按患者展开授权清单（签署时序倒序，最新在前）。
     *
     * @param patientId 患者主索引，非空
     * @return 授权行清单（无授权为空清单非 null）
     */
    @Override
    @Transactional(readOnly = true)
    public List<PrivacyAuth> listByPatient(long patientId) {
        // 数据库读操作：索引 idx_privacy_auth_patient 命中 patient_id 过滤，业务时序倒序展示
        return lambdaQuery()
                .eq(PrivacyAuth::getPatientId, patientId)
                .orderByDesc(PrivacyAuth::getSignedAt)
                .list();
    }

    /**
     * 派生状态（GET /privacy-auths 出参组装用）：到期自动语义零定时任务——库值恒存 EFFECTIVE，
     * EXPIRED 仅在读侧按 valid_to 相对当前时刻派生，不回写库。
     *
     * @param auth 授权行实体，非空；来源：listByPatient 查询结果
     * @return 派生状态：REVOKED 原样（落库终态不复活）；EFFECTIVE 且 valid_to 非空且早于当前时刻
     *         为 EXPIRED；其余（含长期有效 valid_to 空）原值透出
     */
    public static String deriveStatus(PrivacyAuth auth) {
        // 撤回为落库终态：读侧不复活
        if (PrivacyAuthStatus.REVOKED.name().equals(auth.getStatus())) {
            return auth.getStatus();
        }
        // 到期自动：EFFECTIVE 且失效时刻已过当前时刻（valid_to 空=长期有效不派生）
        if (PrivacyAuthStatus.EFFECTIVE.name().equals(auth.getStatus())
                && auth.getValidTo() != null
                && auth.getValidTo().isBefore(OffsetDateTime.now())) {
            return PrivacyAuthStatus.EXPIRED.name();
        }
        return auth.getStatus();
    }
}
