package com.fuyun.patient.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.patient.entity.PrivacyAuth;
import java.util.List;

/**
 * 隐私授权 IService（A.4.3-20）：知情同意落痕 + 授权清单查询 + 派生状态（Task 12 交付；
 * 撤回经库值 REVOKED 派生承载，读侧原样透出不复活）。
 */
public interface IPrivacyAuthService extends IService<PrivacyAuth> {

    /**
     * 登记一条知情同意授权（建档事务内调用，FU-M02-01 强制采集）。
     *
     * @param patientId    患者主索引，非空
     * @param authBasisRef 授权依据引用（纸质凭证编号/电子签名引用），非空
     * @return 授权行 id
     */
    Long recordInformedConsent(long patientId, String authBasisRef);

    /**
     * 按患者展开授权清单（GET /privacy-auths 数据源）。
     *
     * @param patientId 患者主索引，非空
     * @return 授权行清单（签署时序倒序，最新在前；无授权为空清单非 null）
     */
    List<PrivacyAuth> listByPatient(long patientId);
}
