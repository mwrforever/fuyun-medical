package com.fuyun.patient.service;

import com.fuyun.patient.dto.PrivacyMaskRuleUpdateRequest;
import com.fuyun.patient.vo.PatientVO;
import com.fuyun.patient.vo.PrivacyMaskRuleVO;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 隐私脱敏引擎（FU-M02-06 展示侧）：privacy_mask_rule 集中规则 + SensitiveMasker 组合 +
 * 角色豁免判定 + 规则维护；各端展示统一经本引擎，明文只经明文查阅 API（双留痕）。
 */
public interface IPrivacyMaskService {

    /**
     * 就地脱敏档案出参清单（一次加载规则逐行应用；返回同引用便于链式）。
     *
     * @param patients 出参清单，非空（可空字段按 null 透传）
     * @return 同引用清单（敏感字段已替换为脱敏文本）
     */
    List<PatientVO> applyAll(List<PatientVO> patients);

    /**
     * 判定角色清单对目标字段是否豁免（明文查阅权限校验复用）。
     *
     * @param roles       角色编码清单（RoleContextHolder 取值），非空
     * @param targetField 目标字段词（PrivacyConstants.TARGET_*），非空
     * @return true=存在豁免角色（可见明文）；false=无豁免（明文查阅 403）
     */
    boolean isExempt(List<String> roles, String targetField);

    /**
     * 批量豁免判定（明文查阅多字段场景）：规则单次装载内存复用，判定面规则查询数
     * 与请求字段数解耦（OPT-11，A.4.3-14）；逐字段判定口径与 {@link #isExempt} 完全一致。
     *
     * @param roles        角色编码清单（RoleContextHolder 取值），非空
     * @param targetFields 目标字段词清单（来自明文查阅请求 fields），非空、可含重复词
     * @return 豁免字段词集合（清单中判定为豁免的词去重；空集=无任何字段豁免）
     */
    Set<String> exemptFields(List<String> roles, Collection<String> targetFields);

    /**
     * 规则清单（GET /privacy-mask-rules 数据源；VO 化：exemptRoles 拆分清单输出）。
     *
     * @return 规则清单（种子固定 5 行量级），非空
     */
    List<PrivacyMaskRuleVO> listRules();

    /**
     * 规则维护（PUT /privacy-mask-rules/{ruleCode} 端点用例，部分更新语义：非空字段覆盖库值）；
     * SEC-01 安全收口：仅 ADMIN 角色可维护（非 ADMIN 一律 PAT-1024 403 前置拒绝，规则行零触达）。
     *
     * @param ruleCode 规则编码（业务唯一），非空
     * @param request  维护请求（部分更新语义），非空
     * @return 维护后规则出参，非空
     * @throws com.fuyun.common.exception.BizException PAT-1024（403 非 ADMIN 角色，SEC-01 门禁）
     *                                                 / PAT-1021（404 规则编码无命中）
     */
    PrivacyMaskRuleVO updateRule(String ruleCode, PrivacyMaskRuleUpdateRequest request);
}
