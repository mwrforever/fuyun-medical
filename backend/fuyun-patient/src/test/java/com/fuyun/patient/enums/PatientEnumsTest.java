package com.fuyun.patient.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 患者域枚举词表单测（A.2-7 code↔enum 双向映射契约）：词表取值与声明顺序即落库词表，
 * 锁定全集防漂移；of 委托 valueOf 的双向映射与未知值显式拒绝口径逐词表验证。
 */
class PatientEnumsTest {

    @Test
    @DisplayName("状态机词表全集与 of 双向映射（患者/标识/合并/疑似重复/健康项/一卡通/隐私授权）")
    void stateMachineVocabulariesMapByCode() {
        assertThat(PatientStatus.values()).extracting(Enum::name).containsExactly("NORMAL", "FROZEN", "MERGED");
        assertThat(PatientStatus.of("FROZEN")).isEqualTo(PatientStatus.FROZEN);
        assertThat(IdentifierStatus.values())
                .extracting(Enum::name)
                .containsExactly("ACTIVE", "LOST", "REPLACED", "DISABLED");
        assertThat(IdentifierStatus.of("LOST")).isEqualTo(IdentifierStatus.LOST);
        assertThat(MergeStatus.values())
                .extracting(Enum::name)
                .containsExactly("PROCESSING", "COMPLETED", "FAILED", "REVERSED");
        assertThat(MergeStatus.of("REVERSED")).isEqualTo(MergeStatus.REVERSED);
        assertThat(DuplicateStatus.values()).extracting(Enum::name).containsExactly("PENDING", "MERGED", "EXCLUDED");
        assertThat(DuplicateStatus.of("PENDING")).isEqualTo(DuplicateStatus.PENDING);
        assertThat(HealthItemStatus.values()).extracting(Enum::name).containsExactly("ACTIVE", "CORRECTED");
        assertThat(HealthItemStatus.of("CORRECTED")).isEqualTo(HealthItemStatus.CORRECTED);
        assertThat(CardAccountStatus.values()).extracting(Enum::name).containsExactly("ACTIVE", "FROZEN", "CLOSED");
        assertThat(CardAccountStatus.of("CLOSED")).isEqualTo(CardAccountStatus.CLOSED);
        assertThat(PrivacyAuthStatus.values())
                .extracting(Enum::name)
                .containsExactly("EFFECTIVE", "EXPIRED", "REVOKED");
        assertThat(PrivacyAuthStatus.of("REVOKED")).isEqualTo(PrivacyAuthStatus.REVOKED);
    }

    @Test
    @DisplayName("标识/建档/匹配词表全集与 of 双向映射（IdentifierType/RegisterChannel/ArchiveSource/MatchOutcome/DuplicateSource）")
    void identifierAndRegistrationVocabulariesMapByCode() {
        assertThat(IdentifierType.values())
                .extracting(Enum::name)
                .containsExactly(
                        "ID_CARD",
                        "PASSPORT",
                        "MILITARY_OFFICER",
                        "OTHER_LEGAL",
                        "INSURANCE_ELECTRONIC",
                        "HEALTH_CARD",
                        "VISIT_CARD",
                        "MEDICAL_RECORD_NO");
        assertThat(IdentifierType.of("HEALTH_CARD")).isEqualTo(IdentifierType.HEALTH_CARD);
        assertThat(RegisterChannel.values())
                .extracting(Enum::name)
                .containsExactly("WINDOW", "SELF_SERVICE", "ONLINE", "INPATIENT_REGISTER", "EMERGENCY");
        assertThat(RegisterChannel.of("EMERGENCY")).isEqualTo(RegisterChannel.EMERGENCY);
        assertThat(ArchiveSource.values())
                .extracting(Enum::name)
                .containsExactly("STANDARD", "TEMP_ANONYMOUS", "TEMP_NEWBORN");
        assertThat(ArchiveSource.of("TEMP_ANONYMOUS")).isEqualTo(ArchiveSource.TEMP_ANONYMOUS);
        assertThat(MatchOutcome.values()).extracting(Enum::name).containsExactly("AUTO_MATCH", "SUSPECT", "NO_MATCH");
        assertThat(MatchOutcome.of("AUTO_MATCH")).isEqualTo(MatchOutcome.AUTO_MATCH);
        assertThat(DuplicateSource.values()).extracting(Enum::name).containsExactly("REGISTER_SCAN", "BATCH_SCAN");
        assertThat(DuplicateSource.of("BATCH_SCAN")).isEqualTo(DuplicateSource.BATCH_SCAN);
    }

    @Test
    @DisplayName("健康档案项类型词表全集与 of 双向映射（对齐区域平台基本健康信息口径）")
    void healthItemTypeVocabularyMapsByCode() {
        assertThat(HealthItemType.values())
                .extracting(Enum::name)
                .containsExactly("ALLERGY", "CHRONIC", "SURGERY", "VACCINATION");
        assertThat(HealthItemType.of("ALLERGY")).isEqualTo(HealthItemType.ALLERGY);
        assertThat(PrivacyAuthType.values())
                .extracting(Enum::name)
                .containsExactly("INFORMED_CONSENT", "SENSITIVE_USE", "GUARDIAN");
        assertThat(PrivacyAuthType.of("GUARDIAN")).isEqualTo(PrivacyAuthType.GUARDIAN);
    }

    @Test
    @DisplayName("脱敏目标字段：落库词表小驼峰、ofColumn 反查与未知词显式拒绝（词表收口点）")
    void maskTargetFieldColumnVocabularyRoundTrips() {
        assertThat(MaskTargetField.values())
                .extracting(Enum::name)
                .containsExactly("NAME", "ID_CARD_NO", "MOBILE", "ADDRESS", "BIRTH_DATE");
        assertThat(MaskTargetField.NAME.column()).isEqualTo("name");
        assertThat(MaskTargetField.ID_CARD_NO.column()).isEqualTo("idCardNo");
        assertThat(MaskTargetField.MOBILE.column()).isEqualTo("mobile");
        assertThat(MaskTargetField.ADDRESS.column()).isEqualTo("address");
        assertThat(MaskTargetField.BIRTH_DATE.column()).isEqualTo("birthDate");
        // 反查与正查互逆：落库词 → 枚举 → 落库词
        for (MaskTargetField field : MaskTargetField.values()) {
            assertThat(MaskTargetField.ofColumn(field.column())).isEqualTo(field);
            assertThat(MaskTargetField.of(field.name())).isEqualTo(field);
        }
        assertThatThrownBy(() -> MaskTargetField.ofColumn("phone"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知脱敏目标字段落库词");
    }

    @Test
    @DisplayName("未知 code 显式拒绝：of 对词表外值抛 IllegalArgumentException（脏数据不静默）")
    void unknownCodeIsRejectedLoudly() {
        assertThatThrownBy(() -> PatientStatus.of("ARCHIVED")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentifierType.of("WORK_PERMIT")).isInstanceOf(IllegalArgumentException.class);
    }
}
