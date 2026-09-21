package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.service.IPatientIdentifierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 标识介质解析实现单测（PR-5 Task 5 portal 匿名通道消费面）：锚定「ACTIVE 命中→主档归一返回」与
 * 「无 ACTIVE 命中→PAT-1001 原样透出」两条语义（明文不入日志由实现侧 resolveActive 承载）。
 */
@ExtendWith(MockitoExtension.class)
class PatientIdentityQueryImplTest {

    @Mock
    private IPatientIdentifierService identifierService;

    @Mock
    private PatientContextResolver patientContextResolver;

    private PatientIdentityQueryImpl query;

    @BeforeEach
    void setUp() {
        query = new PatientIdentityQueryImpl(identifierService, patientContextResolver);
    }

    @Test
    @DisplayName("resolveActivePatientId：ACTIVE 标识命中且持卡档案为从档——收敛主档返回 resolvedPatientId")
    void resolveReturnsMergedPrimaryPatientId() {
        PatientIdentifier identifier = new PatientIdentifier();
        identifier.setId(3L);
        identifier.setPatientId(20L);
        identifier.setIdentifierType("ID_CARD");
        identifier.setStatus("ACTIVE");
        when(identifierService.resolveActive("ID_CARD", "110101199001010031")).thenReturn(identifier);
        when(patientContextResolver.resolve(20L)).thenReturn(new PatientContextView(20L, 8L, "NORMAL", false, ""));

        long patientId = query.resolveActivePatientId("ID_CARD", "110101199001010031");

        assertThat(patientId).isEqualTo(8L);
    }

    @Test
    @DisplayName("resolveActivePatientId：无 ACTIVE 命中（未登记/挂失失效）——PAT-1001 业务异常原样透出")
    void resolvePropagatesPatErrorWhenNoActiveHit() {
        when(identifierService.resolveActive("VISIT_CARD", "V0000001"))
                .thenThrow(new BizException(
                        com.fuyun.patient.api.PatientErrorCode.PATIENT_NOT_FOUND,
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "标识未登记或已失效"));

        assertThatThrownBy(() -> query.resolveActivePatientId("VISIT_CARD", "V0000001"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(com.fuyun.patient.api.PatientErrorCode.PATIENT_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
                });
    }
}
