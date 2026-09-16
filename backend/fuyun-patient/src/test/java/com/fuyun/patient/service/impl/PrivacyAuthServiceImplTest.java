package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.patient.entity.PrivacyAuth;
import com.fuyun.patient.mapper.PrivacyAuthMapper;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
}
