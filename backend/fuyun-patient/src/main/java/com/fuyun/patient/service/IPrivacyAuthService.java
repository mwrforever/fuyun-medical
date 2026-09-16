package com.fuyun.patient.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.patient.entity.PrivacyAuth;

/**
 * 隐私授权 IService（A.4.3-20）：知情同意落痕本任务交付；授权查询/撤回/派生状态随 Task 12 扩充。
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
}
