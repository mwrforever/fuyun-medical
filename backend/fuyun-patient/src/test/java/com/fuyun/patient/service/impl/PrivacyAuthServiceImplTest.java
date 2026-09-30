package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.dto.PrivacyAuthCreateRequest;
import com.fuyun.patient.entity.PrivacyAuth;
import com.fuyun.patient.mapper.PrivacyAuthMapper;
import com.fuyun.patient.vo.PrivacyAuthVO;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 隐私授权实现单测：知情同意登记落痕口径——auth_type=INFORMED_CONSENT、初值 EFFECTIVE、
 * signed_at 落当前时刻、valid_to 空=长期有效（FU-M02-01/06 建档强制采集载体）；授权登记
 * 领域用例（实体组装/时刻派生/落库/出参派生状态组装）与清单出参组装的 service 层承载口径。
 */
@ExtendWith(MockitoExtension.class)
class PrivacyAuthServiceImplTest {

    @Mock
    private PrivacyAuthMapper privacyAuthMapper;

    private PrivacyAuthServiceImpl privacyAuthService;

    @BeforeAll
    static void initTableInfo() {
        // 单测容器外手动初始化实体表信息（模块内既有 ServiceImpl 单测同款）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PrivacyAuth.class);
    }

    @BeforeEach
    void setUp() {
        privacyAuthService = new PrivacyAuthServiceImpl();
        // 容器外单测：baseMapper 以反射注入（MdmSubscriptionServiceImplTest 同款）
        ReflectionTestUtils.setField(privacyAuthService, "baseMapper", privacyAuthMapper);
        ReflectionTestUtils.setField(privacyAuthService, "entityClass", PrivacyAuth.class);
    }

    @Test
    @DisplayName("登记知情同意：类型/状态/签署时刻按建档口径落行并返回授权行 id")
    void recordInformedConsentPersistsEffectiveAuthRow() {
        // 模拟 ASSIGN_ID 插入期回填主键（与生产参数处理器行为一致）
        Mockito.when(privacyAuthMapper.insert(Mockito.any(PrivacyAuth.class))).thenAnswer(inv -> {
            inv.getArgument(0, PrivacyAuth.class).setId(66L);
            return 1;
        });

        Long id = privacyAuthService.recordInformedConsent(778L, "paper-001");

        assertThat(id).isEqualTo(66L);
        ArgumentCaptor<PrivacyAuth> captor = ArgumentCaptor.forClass(PrivacyAuth.class);
        Mockito.verify(privacyAuthMapper).insert(captor.capture());
        PrivacyAuth saved = captor.getValue();
        assertThat(saved.getPatientId()).isEqualTo(778L);
        assertThat(saved.getAuthType()).isEqualTo("INFORMED_CONSENT");
        assertThat(saved.getAuthBasis()).isEqualTo("paper-001");
        // 签署时刻为落行瞬间的当前时刻（2 秒容差避免时钟抖动脆弱断言）
        assertThat(saved.getSignedAt()).isCloseTo(OffsetDateTime.now(), within(2, ChronoUnit.SECONDS));
        assertThat(saved.getStatus()).isEqualTo("EFFECTIVE");
        assertThat(saved.getValidTo()).isNull();
    }

    @Test
    @DisplayName("授权登记：请求字段组装实体落行（初值 EFFECTIVE）并回显派生状态出参")
    void createAuthPersistsAssembledAuthRowAndReturnsVo() {
        // 模拟 ASSIGN_ID 插入期回填主键（与生产参数处理器行为一致）
        Mockito.when(privacyAuthMapper.insert(Mockito.any(PrivacyAuth.class))).thenAnswer(inv -> {
            inv.getArgument(0, PrivacyAuth.class).setId(66L);
            return 1;
        });

        PrivacyAuthVO vo = privacyAuthService.createAuth(new PrivacyAuthCreateRequest(
                778L, "SENSITIVE_USE", "paper-002", "科研利用", "2026-09-01T10:00:00+08:00", "2027-09-01T10:00:00+08:00"));

        ArgumentCaptor<PrivacyAuth> captor = ArgumentCaptor.forClass(PrivacyAuth.class);
        Mockito.verify(privacyAuthMapper).insert(captor.capture());
        PrivacyAuth saved = captor.getValue();
        assertThat(saved.getPatientId()).isEqualTo(778L);
        assertThat(saved.getAuthType()).isEqualTo("SENSITIVE_USE");
        assertThat(saved.getAuthBasis()).isEqualTo("paper-002");
        assertThat(saved.getScope()).isEqualTo("科研利用");
        // 时刻派生：非空合法 ISO 文本按值落列，不走空值回落
        assertThat(saved.getSignedAt()).isEqualTo(OffsetDateTime.parse("2026-09-01T10:00:00+08:00"));
        assertThat(saved.getValidTo()).isEqualTo(OffsetDateTime.parse("2027-09-01T10:00:00+08:00"));
        assertThat(saved.getStatus()).isEqualTo("EFFECTIVE");
        // 出参回显：主键回填 + 派生状态（登记值未到期 → EFFECTIVE）
        assertThat(vo.id()).isEqualTo(66L);
        assertThat(vo.patientId()).isEqualTo(778L);
        assertThat(vo.authType()).isEqualTo("SENSITIVE_USE");
        assertThat(vo.derivedStatus()).isEqualTo("EFFECTIVE");
    }

    @Test
    @DisplayName("授权登记：签署/失效时刻空文本按语义回落（签署=当前时刻、失效=长期有效 null）")
    void createAuthFallsBackMomentsWhenBlank() {
        Mockito.when(privacyAuthMapper.insert(Mockito.any(PrivacyAuth.class))).thenAnswer(inv -> {
            inv.getArgument(0, PrivacyAuth.class).setId(66L);
            return 1;
        });

        PrivacyAuthVO vo = privacyAuthService.createAuth(
                new PrivacyAuthCreateRequest(778L, "GUARDIAN", "paper-003", null, "  ", null));

        ArgumentCaptor<PrivacyAuth> captor = ArgumentCaptor.forClass(PrivacyAuth.class);
        Mockito.verify(privacyAuthMapper).insert(captor.capture());
        PrivacyAuth saved = captor.getValue();
        // 签署时刻为落行瞬间的当前时刻（2 秒容差避免时钟抖动脆弱断言）；失效时刻空=长期有效
        assertThat(saved.getSignedAt()).isCloseTo(OffsetDateTime.now(), within(2, ChronoUnit.SECONDS));
        assertThat(saved.getValidTo()).isNull();
        assertThat(vo.validTo()).isNull();
        assertThat(vo.derivedStatus()).isEqualTo("EFFECTIVE");
    }

    @Test
    @DisplayName("授权登记：signedAtIso 非 ISO 时刻拒绝 PAT-1023（400）不落库（D-15 语义随迁 service）")
    void createAuthRejectsMalformedSignedAtIsoAsPat1023() {
        assertThatThrownBy(() -> privacyAuthService.createAuth(new PrivacyAuthCreateRequest(
                        778L, "SENSITIVE_USE", "paper-002", null, "2026-13-40 08:00", null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("signedAtIso");
                });
        // 解析守卫前置：拒绝路径不产生任何落库
        Mockito.verify(privacyAuthMapper, Mockito.never()).insert(Mockito.any(PrivacyAuth.class));
    }

    @Test
    @DisplayName("授权登记：validToIso 非 ISO 时刻同样拒绝 PAT-1023（400）不落库")
    void createAuthRejectsMalformedValidToIsoAsPat1023() {
        assertThatThrownBy(() -> privacyAuthService.createAuth(
                        new PrivacyAuthCreateRequest(778L, "SENSITIVE_USE", "paper-002", null, null, "not-a-time")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("validToIso");
                });
        Mockito.verify(privacyAuthMapper, Mockito.never()).insert(Mockito.any(PrivacyAuth.class));
    }

    @Test
    @DisplayName("授权清单出参：patient_id 过滤签署时序倒序，Entity→VO 与派生状态在 service 组装")
    void listAuthVosByPatientMapsRowsWithDerivedStatus() {
        PrivacyAuth expiredRow = auth("EFFECTIVE", OffsetDateTime.now().minusMinutes(1));
        PrivacyAuth revokedRow = auth("REVOKED", null);
        Mockito.when(privacyAuthMapper.selectList(Mockito.any(Wrapper.class)))
                .thenReturn(List.of(expiredRow, revokedRow));

        List<PrivacyAuthVO> vos = privacyAuthService.listAuthVosByPatient(778L);

        // 出参组装：id 透出 + 派生状态（到期 EXPIRED / 终态 REVOKED 原样不复活）
        assertThat(vos).hasSize(2);
        assertThat(vos.get(0).id()).isEqualTo(9L);
        assertThat(vos.get(0).derivedStatus()).isEqualTo("EXPIRED");
        assertThat(vos.get(1).derivedStatus()).isEqualTo("REVOKED");
        ArgumentCaptor<Wrapper<PrivacyAuth>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(privacyAuthMapper).selectList(captor.capture());
        // MP 3.5.17 wrapper 断言子串 contains：过滤列与排序列锁定，禁绑定 SQL 全文
        assertThat(captor.getValue().getSqlSegment()).contains("patient_id").contains("signed_at");
    }

    /** 授权行替身（派生状态入参：库值 status + valid_to 两列为派生判定唯一依据） */
    private PrivacyAuth auth(String status, OffsetDateTime validTo) {
        PrivacyAuth auth = new PrivacyAuth();
        auth.setId(9L);
        auth.setPatientId(778L);
        auth.setStatus(status);
        auth.setValidTo(validTo);
        return auth;
    }

    @Test
    @DisplayName("派生状态：库值 REVOKED 原样透出（撤回为落库终态，读侧不复活）")
    void deriveStatusKeepsRevokedAsIs() {
        assertThat(PrivacyAuthServiceImpl.deriveStatus(auth("REVOKED", null))).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("派生状态：EFFECTIVE 且 valid_to 已过当前时刻派生 EXPIRED（到期自动语义，零定时任务）")
    void deriveStatusDerivesExpiredWhenValidToPast() {
        assertThat(PrivacyAuthServiceImpl.deriveStatus(
                        auth("EFFECTIVE", OffsetDateTime.now().minusMinutes(1))))
                .isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("派生状态：EFFECTIVE 未到期与长期有效（valid_to 空）均原值透出")
    void deriveStatusKeepsEffectiveWhenNotExpiredOrOpenEnded() {
        assertThat(PrivacyAuthServiceImpl.deriveStatus(
                        auth("EFFECTIVE", OffsetDateTime.now().plusDays(1))))
                .isEqualTo("EFFECTIVE");
        assertThat(PrivacyAuthServiceImpl.deriveStatus(auth("EFFECTIVE", null))).isEqualTo("EFFECTIVE");
    }
}
