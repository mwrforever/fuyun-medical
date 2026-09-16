package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.mapper.PatientIdentifierMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 患者标识注册表实现单测：挂接加密落库（密文+盲索引+初值 ACTIVE）与唯一索引兜底语义——
 * 并发重复登记以 DuplicateKeyException 转 PAT-1002 业务失败（数据库最终兜底，PAT-1002 唯一口径）。
 */
@ExtendWith(MockitoExtension.class)
class PatientIdentifierServiceImplTest {

    @Mock
    private PatientIdentifierMapper identifierMapper;

    @Mock
    private PatientFieldCrypto crypto;

    private PatientIdentifierServiceImpl identifierService;

    @BeforeAll
    static void initTableInfo() {
        // 单测容器外手动初始化实体表信息（模块内既有 ServiceImpl 单测同款）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PatientIdentifier.class);
    }

    @BeforeEach
    void setUp() {
        identifierService = new PatientIdentifierServiceImpl(crypto);
        // 容器外单测：baseMapper 以反射注入（MdmSubscriptionServiceImplTest 同款）
        ReflectionTestUtils.setField(identifierService, "baseMapper", identifierMapper);
        ReflectionTestUtils.setField(identifierService, "entityClass", PatientIdentifier.class);
    }

    @Test
    @DisplayName("挂接成功：密文与盲索引落库、初值 ACTIVE/主标识按入参，返回标识行 id")
    void attachEncryptsAndPersistsIdentifier() {
        when(crypto.encrypt(any())).thenReturn("cipher-text");
        when(crypto.hash(any())).thenReturn("hash-text");
        // 模拟 ASSIGN_ID 插入期回填主键（与生产参数处理器行为一致）
        when(identifierMapper.insert(any(PatientIdentifier.class))).thenAnswer(inv -> {
            inv.getArgument(0, PatientIdentifier.class).setId(55L);
            return 1;
        });

        Long id = identifierService.attach(1L, "ID_CARD", "110101199003077890", null, true);

        assertThat(id).isEqualTo(55L);
        ArgumentCaptor<PatientIdentifier> captor = ArgumentCaptor.forClass(PatientIdentifier.class);
        verify(identifierMapper).insert(captor.capture());
        PatientIdentifier saved = captor.getValue();
        assertThat(saved.getPatientId()).isEqualTo(1L);
        assertThat(saved.getIdentifierType()).isEqualTo("ID_CARD");
        assertThat(saved.getIdentifierValueCipher()).isEqualTo("cipher-text");
        assertThat(saved.getValueHash()).isEqualTo("hash-text");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getIsPrimary()).isTrue();
    }

    @Test
    @DisplayName("标识已挂其他档案：唯一约束命中转 PAT-1002 业务失败（409，人工核对口径）")
    void attachOnDuplicateKeySurfacesAsPat1002() {
        when(crypto.encrypt(any())).thenReturn("cipher-text");
        when(crypto.hash(any())).thenReturn("hash-text");
        when(identifierMapper.insert(any(PatientIdentifier.class)))
                .thenThrow(new DuplicateKeyException("uk_patient_identifier_type_hash"));

        assertThatThrownBy(() -> identifierService.attach(2L, "HEALTH_CARD", "H32019900101", "CARD-0001", false))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.IDENTIFIER_ALREADY_BOUND));
    }
}
