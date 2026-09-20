package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.context.RoleContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.CareRelationQuery;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.dto.UnmaskRequest;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.entity.PrivacyAccessLog;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.mapper.PrivacyAccessLogMapper;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.service.PrivacyMaskService;
import com.fuyun.patient.vo.UnmaskVO;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

/**
 * 明文查阅 D-16 三态门禁单测（CareRelationQuery SPI 第二道校验）：角色豁免命中时豁免短路
 * 不探测诊疗关系；无豁免且 SPI 在途诊疗关系命中放行（台账照落）；无豁免且无诊疗关系 403
 * 不落台账；SPI 无实现（ObjectProvider 取值 null）空安全维持原角色豁免单门禁。
 */
@ExtendWith(MockitoExtension.class)
class PrivacyCareRelationGateTest {

    private static final long PATIENT_ID = 5L;

    @Mock
    private PrivacyMaskService privacyMaskService;

    @Mock
    private IPatientService patientService;

    @Mock
    private PrivacyAccessLogMapper privacyAccessLogMapper;

    @Mock
    private PatientFieldCrypto crypto;

    @Mock
    private CareRelationQuery careRelationQuery;

    @Mock
    private ObjectProvider<CareRelationQuery> careRelationProvider;

    @BeforeAll
    static void initTableInfo() {
        // 台账落痕走 mapper.insert：实体表信息按模块内既有单测同款初始化（listAccessLogs 分页同依赖）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PrivacyAccessLog.class);
    }

    @BeforeEach
    void setUp() {
        RoleContextHolder.set(List.of("DOCTOR"));
        OperatorContextHolder.set("op-001");
    }

    @AfterEach
    void tearDown() {
        RoleContextHolder.clear();
        OperatorContextHolder.clear();
        MDC.clear();
    }

    /** 档案替身：敏感列密文占位（明文经 crypto 替身解出，密文值不出现在断言外） */
    private Patient patient() {
        Patient patient = new Patient();
        patient.setPatientId(PATIENT_ID);
        patient.setName("张三");
        patient.setMobileCipher("mobile-cipher");
        patient.setBirthDate(LocalDate.of(1990, 3, 7));
        return patient;
    }

    /** 构造带 SPI 实现的查阅服务（ObjectProvider 解析返回注册实现） */
    private PrivacyServiceImpl serviceWithRegisteredSpi() {
        when(careRelationProvider.getIfAvailable()).thenReturn(careRelationQuery);
        return new PrivacyServiceImpl(
                privacyMaskService, patientService, privacyAccessLogMapper, crypto, careRelationProvider);
    }

    @Test
    @DisplayName("角色豁免命中：豁免短路第二道不触发，诊疗关系 SPI 零探测")
    void unmaskPassesOnRoleExemptWithoutCareRelationProbe() {
        PrivacyServiceImpl privacyService = serviceWithRegisteredSpi();
        when(privacyMaskService.isExempt(anyList(), anyString())).thenReturn(true);
        when(patientService.getById(PATIENT_ID)).thenReturn(patient());

        UnmaskVO vo = privacyService.unmask(new UnmaskRequest(PATIENT_ID, List.of("name"), "临床核验"));

        assertThat(vo.values()).containsEntry("name", "张三");
        // 豁免短路：第二道校验不触发（无探测开销）
        verifyNoInteractions(careRelationQuery);
        verify(privacyAccessLogMapper).insert(any(PrivacyAccessLog.class));
    }

    @Test
    @DisplayName("无豁免但 SPI 命中在途诊疗关系：放行明文查阅，台账照落（操作者标识入探测）")
    void unmaskPassesOnCareRelationWhenNotExempt() {
        PrivacyServiceImpl privacyService = serviceWithRegisteredSpi();
        when(privacyMaskService.isExempt(anyList(), anyString())).thenReturn(false);
        when(careRelationQuery.hasCareRelation(eq(PATIENT_ID), eq("op-001"))).thenReturn(true);
        when(patientService.getById(PATIENT_ID)).thenReturn(patient());
        when(crypto.decrypt("mobile-cipher")).thenReturn("13800001234");

        UnmaskVO vo = privacyService.unmask(new UnmaskRequest(PATIENT_ID, List.of("mobile"), "临床核验"));

        assertThat(vo.values()).containsEntry("mobile", "13800001234");
        // 200 语义：台账照落（查阅事实成立，留痕完整）
        ArgumentCaptor<PrivacyAccessLog> captor = ArgumentCaptor.forClass(PrivacyAccessLog.class);
        verify(privacyAccessLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getOperatorId()).isEqualTo("op-001");
        assertThat(captor.getValue().getAccessType()).isEqualTo("UNMASK_QUERY");
    }

    @Test
    @DisplayName("无豁免且无在途诊疗关系：PAT-1018/403（无豁免角色且无在途诊疗关系），不落查阅台账")
    void unmaskRejectsWhenNeitherExemptNorCareRelation() {
        PrivacyServiceImpl privacyService = serviceWithRegisteredSpi();
        when(privacyMaskService.isExempt(anyList(), anyString())).thenReturn(false);
        when(careRelationQuery.hasCareRelation(eq(PATIENT_ID), eq("op-001"))).thenReturn(false);

        assertThatThrownBy(() -> privacyService.unmask(new UnmaskRequest(PATIENT_ID, List.of("mobile"), "临床核验")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PatientErrorCode.UNMASK_NOT_AUTHORIZED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getMessage()).isEqualTo("无豁免角色且无在途诊疗关系");
                });

        // 403 分支无查阅事实：档案解密链零触达、台账零落行（留痕归审计切面 FAIL 行）
        verifyNoInteractions(patientService, crypto);
        verify(privacyAccessLogMapper, never()).insert(any(PrivacyAccessLog.class));
    }

    @Test
    @DisplayName("SPI 无实现（ObjectProvider 解析 null）：无豁免仍走原 PAT-1018 单门禁，空安全不 NPE")
    void unmaskKeepsSingleGateWhenNoCareRelationBeanRegistered() {
        // 不桩 getIfAvailable：容器无实现语义（Mockito 默认返回 null），构造期取值不得 NPE
        PrivacyServiceImpl privacyService = new PrivacyServiceImpl(
                privacyMaskService, patientService, privacyAccessLogMapper, crypto, careRelationProvider);
        when(privacyMaskService.isExempt(anyList(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> privacyService.unmask(new UnmaskRequest(PATIENT_ID, List.of("mobile"), "临床核验")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PatientErrorCode.UNMASK_NOT_AUTHORIZED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getMessage()).isEqualTo("无明文查阅权限");
                });

        verifyNoInteractions(careRelationQuery);
    }
}
