package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.patient.entity.PrivacyAuth;
import com.fuyun.patient.mapper.PrivacyAuthMapper;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 隐私授权实现单测：知情同意登记落痕口径——auth_type=INFORMED_CONSENT、初值 EFFECTIVE、
 * signed_at 落当前时刻、valid_to 空=长期有效（FU-M02-01/06 建档强制采集载体）。
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

    @Test
    @DisplayName("按患者展开授权清单：签署时序倒序（wrapper 锁 patient_id 过滤与 signed_at 降序）")
    void listByPatientOrdersBySignedAtDesc() {
        PrivacyAuth row = auth("EFFECTIVE", null);
        Mockito.when(privacyAuthMapper.selectList(Mockito.any(Wrapper.class))).thenReturn(List.of(row));

        List<PrivacyAuth> rows = privacyAuthService.listByPatient(778L);

        assertThat(rows).containsExactly(row);
        ArgumentCaptor<Wrapper<PrivacyAuth>> captor = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(privacyAuthMapper).selectList(captor.capture());
        // MP 3.5.17 wrapper 断言子串 contains：过滤列与排序列锁定，禁绑定 SQL 全文
        assertThat(captor.getValue().getSqlSegment()).contains("patient_id").contains("signed_at");
    }
}
