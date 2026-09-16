package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.patient.entity.PrivacyAuth;
import com.fuyun.patient.mapper.PrivacyAuthMapper;
import com.fuyun.patient.service.IPrivacyAuthService;
import java.time.OffsetDateTime;

/**
 * 隐私授权实现（patient.privacy_auth 主表）：知情同意初值 EFFECTIVE、valid_to 空=长期有效；
 * EXPIRED 派生与撤回语义随 Task 12 交付。
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
}
