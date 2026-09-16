package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.internal.PatientDomainEvent;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.mapper.PatientIdentifierMapper;
import com.fuyun.patient.service.IPatientService;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 患者标识注册表实现单测：挂接加密落库（密文+盲索引+初值 ACTIVE）与唯一索引兜底语义、
 * ACTIVE 等值解析（挂失/解绑/替换即解析失效）、按档案展开清单与 identifier.changed
 * 事件载荷只携 valueHash 不携明文（解析缓存失效依据）。
 */
@ExtendWith(MockitoExtension.class)
class PatientIdentifierServiceImplTest {

    @Mock
    private PatientIdentifierMapper identifierMapper;

    @Mock
    private PatientFieldCrypto crypto;

    @Mock
    private IPatientService patientService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    /** 查询替身返回集（链式 list 经 baseMapper.selectList 承载，attach 插入同源） */
    private List<PatientIdentifier> queryResult = List.of();

    private PatientIdentifierServiceImpl identifierService;

    @BeforeAll
    static void initTableInfo() {
        // 单测容器外手动初始化实体表信息（模块内既有 ServiceImpl 单测同款）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PatientIdentifier.class);
    }

    @BeforeEach
    void setUp() {
        identifierService = new PatientIdentifierServiceImpl(crypto, patientService, eventPublisher);
        // 容器外单测：baseMapper 以反射注入（MdmSubscriptionServiceImplTest 同款），链式查询触达 mock mapper
        ReflectionTestUtils.setField(identifierService, "baseMapper", identifierMapper);
        ReflectionTestUtils.setField(identifierService, "entityClass", PatientIdentifier.class);
        // 盲索引桩取摘要形态（不入嵌明文）：changed 事件「载荷不含明文」断言因此真实有效；null 入参透传（重打桩期 Mockito 以 null 触发旧 answer）
        lenient().when(crypto.hash(any())).thenAnswer(inv -> {
            String value = inv.getArgument(0);
            return value == null ? null : "h_" + Integer.toHexString(value.hashCode());
        });
        lenient().when(crypto.encrypt(any())).thenReturn("cipher");
    }

    private PatientIdentifier row(String status) {
        PatientIdentifier r = new PatientIdentifier();
        r.setId(1L);
        r.setPatientId(5L);
        r.setIdentifierType("ID_CARD");
        r.setStatus(status);
        return r;
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

    @Test
    @DisplayName("ACTIVE 标识解析命中返回行（等值盲索引语义由 mock hash 承载）")
    void activeIdentifierResolves() {
        queryResult = List.of(row("ACTIVE"));
        when(identifierMapper.selectList(any())).thenReturn(queryResult);

        PatientIdentifier hit = identifierService.resolveActive("ID_CARD", "110101199003077890");

        assertThat(hit.getStatus()).isEqualTo("ACTIVE");
        assertThat(hit.getPatientId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("挂失（LOST）标识解析即失效：PAT-1001（挂失后解析立即失效语义）")
    void lostIdentifierFailsResolution() {
        queryResult = List.of(row("LOST"));
        when(identifierMapper.selectList(any())).thenReturn(queryResult);

        assertThatThrownBy(() -> identifierService.resolveActive("ID_CARD", "x"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.PATIENT_NOT_FOUND));
    }

    @Test
    @DisplayName("未登记标识（空命中）按未解析拒绝：PAT-1001")
    void unknownIdentifierFailsResolution() {
        when(identifierMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> identifierService.resolveActive("ID_CARD", "x"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.PATIENT_NOT_FOUND));
    }

    @Test
    @DisplayName("按档案展开标识清单：等值 patient_id 且按 bound_at 倒序（wrapper 断言锁定列）")
    void listByPatientQueriesPatientIdOrderedByBoundAt() {
        queryResult = List.of(row("ACTIVE"));
        when(identifierMapper.selectList(any())).thenReturn(queryResult);

        List<PatientIdentifier> rows = identifierService.listByPatient(5L);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPatientId()).isEqualTo(5L);
        // 列分派零回归保护：捕获 wrapper 断言等值列与排序列（先渲染 SQL 片段触发惰性求值，Task 5 口径）
        ArgumentCaptor<Wrapper<PatientIdentifier>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(identifierMapper).selectList(captor.capture());
        assertThat(((LambdaQueryWrapper<PatientIdentifier>) captor.getValue()).getSqlSegment())
                .contains("patient_id")
                .contains("bound_at");
    }

    @Test
    @DisplayName("publishChanged 事件载荷只携 valueHash 不携标识值明文")
    void changedEventCarriesHashOnly() {
        String plaintext = "110101199003077890";
        String expectedHash = "h_" + Integer.toHexString(plaintext.hashCode());
        identifierService.publishChanged(5L, "ID_CARD", plaintext, "BOUND");

        ArgumentCaptor<PatientDomainEvent> captor = ArgumentCaptor.forClass(PatientDomainEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(PatientMessagingConstants.EVENT_IDENTIFIER_CHANGED);
        assertThat(String.valueOf(captor.getValue().payload()))
                .doesNotContain(plaintext)
                .contains(expectedHash);
    }
}
