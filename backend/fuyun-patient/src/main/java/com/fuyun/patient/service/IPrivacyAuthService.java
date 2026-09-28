package com.fuyun.patient.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.patient.dto.PrivacyAuthCreateRequest;
import com.fuyun.patient.entity.PrivacyAuth;
import com.fuyun.patient.vo.PrivacyAuthVO;
import java.util.List;

/**
 * 隐私授权 IService（A.4.3-20）：知情同意落痕 + 授权登记/清单出参领域用例 + 派生状态（Task 12 交付；
 * 撤回经库值 REVOKED 派生承载，读侧原样透出不复活）。授权端点的实体组装/时刻派生/落库/
 * Entity→VO 组装全在 service 承载（controller 仅校验+调用+响应）。
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
     * 授权登记（POST /privacy-auths 领域用例）：知情同意外的授权类型统一登记入口（建档知情同意
     * 走注册事务内 recordInformedConsent）——请求字段组装授权实体、签署/失效时刻空值回落与
     * ISO 解析守卫、落库与出参派生状态组装整体在 service 承载。
     *
     * @param request 登记请求（已经 controller @Valid 必填/词表校验；来源：授权登记表单），非空
     * @return 授权出参（主键落库后回填；派生状态按登记值即时派生）
     * @throws com.fuyun.common.exception.BizException PAT-1023（400）signedAtIso/validToIso 非空
     *                                                 且非合法 ISO-8601 时刻文本（拒绝即不落库）
     */
    PrivacyAuthVO createAuth(PrivacyAuthCreateRequest request);

    /**
     * 按患者展开授权出参清单（GET /privacy-auths 数据源；Entity→VO 与派生状态组装归 service）。
     *
     * @param patientId 患者主索引，非空
     * @return 授权出参清单（签署时序倒序，最新在前；derivedStatus 读侧派生；无授权为空清单非 null）
     */
    List<PrivacyAuthVO> listAuthVosByPatient(long patientId);
}
