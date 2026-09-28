package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.dto.PrivacyAuthCreateRequest;
import com.fuyun.patient.entity.PrivacyAuth;
import com.fuyun.patient.enums.PrivacyAuthStatus;
import com.fuyun.patient.mapper.PrivacyAuthMapper;
import com.fuyun.patient.service.IPrivacyAuthService;
import com.fuyun.patient.vo.PrivacyAuthVO;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 隐私授权实现（patient.privacy_auth 主表）：知情同意登记（Task 5）+ 授权登记/清单出参领域用例
 * （实体组装/时刻派生/落库/Entity→VO 组装归本层承载）+ 派生状态（Task 12）。EXPIRED 为读侧
 * 派生态：valid_to 相对当前时刻判定，零定时任务设计（库值恒存 EFFECTIVE，不回写）；REVOKED 为
 * 落库终态，派生时原样透出不复活。
 */
@Slf4j
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
     * 授权登记（POST /privacy-auths 领域用例）：请求字段组装授权实体，签署/失效时刻空值语义回落
     * （签署空=当前时刻、失效空=长期有效），非空非法 ISO 文本统一经解析守卫转 400 PAT-1023
     * （D-15，不再走全局 500），落库后按登记值即时派生出参状态。
     *
     * @param request 登记请求（已经 controller @Valid 必填/词表校验），非空
     * @return 授权出参（主键 ASSIGN_ID 插入期回填；派生状态按登记值即时派生）
     * @throws BizException PAT-1023（400）signedAtIso/validToIso 非空且非合法 ISO-8601 时刻文本
     */
    @Override
    public PrivacyAuthVO createAuth(PrivacyAuthCreateRequest request) {
        PrivacyAuth auth = new PrivacyAuth();
        auth.setPatientId(request.patientId());
        auth.setAuthType(request.authType());
        auth.setAuthBasis(request.authBasis());
        auth.setScope(request.scope());
        // 签署时刻空=落当前时刻（与建档知情同意同口径）；失效时刻空=长期有效（EXPIRED 读侧派生）
        auth.setSignedAt(parseIsoTime("signedAtIso", request.signedAtIso(), OffsetDateTime.now()));
        auth.setValidTo(parseIsoTime("validToIso", request.validToIso(), null));
        auth.setStatus(PrivacyAuthStatus.EFFECTIVE.name());
        // 数据库写操作：授权行落库（主键 ASSIGN_ID 插入期回填）
        save(auth);
        log.info("隐私授权登记落库：patientId={}，authId={}，authType={}", auth.getPatientId(), auth.getId(), auth.getAuthType());
        return toAuthVO(auth);
    }

    /**
     * 按患者展开授权出参清单（GET /privacy-auths 数据源，签署时序倒序，最新在前）。
     *
     * @param patientId 患者主索引，非空
     * @return 授权出参清单（derivedStatus 读侧派生；无授权为空清单非 null）
     */
    @Override
    @Transactional(readOnly = true)
    public List<PrivacyAuthVO> listAuthVosByPatient(long patientId) {
        // 数据库读操作：索引 idx_privacy_auth_patient 命中 patient_id 过滤，业务时序倒序展示
        return lambdaQuery()
                .eq(PrivacyAuth::getPatientId, patientId)
                .orderByDesc(PrivacyAuth::getSignedAt)
                .list()
                .stream()
                .map(this::toAuthVO)
                .toList();
    }

    /**
     * 派生状态（授权出参组装用）：到期自动语义零定时任务——库值恒存 EFFECTIVE，
     * EXPIRED 仅在读侧按 valid_to 相对当前时刻派生，不回写库。
     *
     * @param auth 授权行实体，非空；来源：listAuthVosByPatient 查询结果或登记落库行
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

    /**
     * 实体→授权出参（含派生状态组装；A.7-3 职责隔离——Entity 不出接口层）。
     *
     * @param auth 授权行实体，非空
     * @return 授权出参（derivedStatus 经 {@link #deriveStatus} 读侧派生）
     */
    private PrivacyAuthVO toAuthVO(PrivacyAuth auth) {
        return new PrivacyAuthVO(
                auth.getId(),
                auth.getPatientId(),
                auth.getAuthType(),
                auth.getAuthBasis(),
                auth.getScope(),
                auth.getSignedAt(),
                auth.getValidTo(),
                deriveStatus(auth));
    }

    /**
     * ISO-8601 时刻解析守卫（D-15 收口）：非空非法 ISO 文本统一转 400 PAT-1023，不再走全局 500
     * （「ProblemDetail + PAT-xxxx」契约红线）；空文本按各字段语义回落（签署时刻=当前时刻、
     * 失效时刻=长期有效 null）。
     *
     * @param fieldName 字段业务名（错误消息与告警定位用），非空
     * @param isoText   ISO-8601 时刻文本，可空（空 → 返回 fallback）
     * @param fallback  空文本回落值（可为 null，语义由调用方字段承载）
     * @return 解析结果或空文本回落值
     * @throws BizException PAT-1023（400）文本非合法 ISO-8601 时刻
     */
    private OffsetDateTime parseIsoTime(String fieldName, String isoText, OffsetDateTime fallback) {
        if (isoText == null || isoText.isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(isoText);
        } catch (DateTimeParseException e) {
            log.warn("隐私授权 {} 非 ISO-8601 时刻文本，拒绝登记", fieldName);
            throw new BizException(
                    PatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    fieldName + " 须为合法 ISO-8601 时刻（如 2026-09-17T10:15:00+08:00）");
        }
    }
}
