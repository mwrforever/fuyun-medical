package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.dto.PrivacyMaskRuleUpdateRequest;
import com.fuyun.patient.entity.PrivacyMaskRule;
import com.fuyun.patient.mapper.PrivacyMaskRuleMapper;
import com.fuyun.patient.vo.PatientVO;
import com.fuyun.patient.vo.PrivacyMaskRuleVO;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 脱敏引擎单测（Spec §10 安全项）：五类规则掩码形态、停用规则透传、豁免角色判定、规则清单与维护。 */
class PrivacyMaskServiceImplTest {

    private PrivacyMaskRuleMapper ruleMapper;

    private PrivacyMaskServiceImpl maskService;

    @BeforeAll
    static void initTableInfo() {
        // updateRule 的 wrapper eq 列解析依赖实体表信息（模块内既有单测同款）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PrivacyMaskRule.class);
    }

    @BeforeEach
    void setUp() {
        ruleMapper = mock(PrivacyMaskRuleMapper.class);
        maskService = new PrivacyMaskServiceImpl(ruleMapper);
        when(ruleMapper.selectList(null))
                .thenReturn(List.of(
                        rule("MASK_NAME", "name", "ADMIN", true),
                        rule("MASK_ID_CARD_NO", "idCardNo", "ADMIN", true),
                        rule("MASK_MOBILE", "mobile", "ADMIN", true),
                        rule("MASK_ADDRESS", "address", "ADMIN", true),
                        rule("MASK_BIRTH_DATE", "birthDate", "ADMIN", true)));
    }

    /** 规则行替身（maskPattern 按词表默认值，引擎按 target_field 分派不读 pattern——保留策略语义在常量词表） */
    private PrivacyMaskRule rule(String code, String target, String roles, boolean enabled) {
        PrivacyMaskRule r = new PrivacyMaskRule();
        r.setRuleCode(code);
        r.setTargetField(target);
        r.setMaskPattern("KEEP");
        r.setExemptRoles(roles);
        r.setEnabled(enabled);
        return r;
    }

    private PatientVO vo() {
        PatientVO vo = new PatientVO();
        vo.setName("张三丰");
        vo.setIdCardNo("110101199003077890");
        vo.setMobile("13800001234");
        vo.setAddress("北京市朝阳区xx路1号");
        vo.setBirthDate(LocalDate.of(1990, 3, 7));
        return vo;
    }

    @Test
    @DisplayName("五类规则齐套输出预期掩码形态（留姓/证件前6后4/手机前3后4/地址留市/生日留年）")
    void allRulesProduceExpectedMaskShapes() {
        PatientVO out = maskService.applyAll(new ArrayList<>(List.of(vo()))).get(0);
        assertThat(out.getName()).isEqualTo("张**");
        assertThat(out.getIdCardNo()).isEqualTo("110101********7890");
        assertThat(out.getMobile()).isEqualTo("138****1234");
        assertThat(out.getAddress()).startsWith("北京市").endsWith("******");
        assertThat(out.getBirthDate()).isEqualTo(LocalDate.of(1990, 1, 1));
    }

    @Test
    @DisplayName("规则停用（enabled=false）字段保持原值透传")
    void disabledRuleKeepsFieldUntouched() {
        when(ruleMapper.selectList(null)).thenReturn(List.of(rule("MASK_NAME_OFF", "name", "ADMIN", false)));
        PatientVO out = maskService.applyAll(new ArrayList<>(List.of(vo()))).get(0);
        assertThat(out.getName()).isEqualTo("张三丰");
    }

    @Test
    @DisplayName("地址掩码边界：无省市分隔符全掩码、州前缀取近者、空值透传")
    void addressMaskEdgeFormsFollowPrefixRule() {
        PatientVO withoutSeparator = vo();
        withoutSeparator.setAddress("朝阳区xx路1号");
        PatientVO out1 =
                maskService.applyAll(new ArrayList<>(List.of(withoutSeparator))).get(0);
        assertThat(out1.getAddress()).isEqualTo("******");
        // 「州」先于「市」出现：保留到最近（更小下标）的省市级分隔符
        PatientVO zhouPrefixed = vo();
        zhouPrefixed.setAddress("荆州市xx区yy路2号");
        PatientVO out2 =
                maskService.applyAll(new ArrayList<>(List.of(zhouPrefixed))).get(0);
        assertThat(out2.getAddress()).startsWith("荆州").endsWith("******");
        // null/空白住址原值透传（可空列语义）
        PatientVO nullAddress = vo();
        nullAddress.setAddress(null);
        PatientVO out3 =
                maskService.applyAll(new ArrayList<>(List.of(nullAddress))).get(0);
        assertThat(out3.getAddress()).isNull();
        PatientVO blankAddress = vo();
        blankAddress.setAddress("   ");
        PatientVO out4 =
                maskService.applyAll(new ArrayList<>(List.of(blankAddress))).get(0);
        assertThat(out4.getAddress()).isEqualTo("   ");
    }

    @Test
    @DisplayName("出生日期 null 透传；未登记词表的目标字段告警跳过不改写任何字段")
    void nullBirthDateAndUnknownTargetFieldFollowFallbackRule() {
        PatientVO nullBirth = vo();
        nullBirth.setBirthDate(null);
        PatientVO out =
                maskService.applyAll(new ArrayList<>(List.of(nullBirth))).get(0);
        assertThat(out.getBirthDate()).isNull();
        // 未识别 target_field：规则跳过（warn 留痕），字段保持原值
        when(ruleMapper.selectList(null)).thenReturn(List.of(rule("MASK_UNKNOWN", "unknownField", "ADMIN", true)));
        PatientVO untouched =
                maskService.applyAll(new ArrayList<>(List.of(vo()))).get(0);
        assertThat(untouched.getName()).isEqualTo("张三丰");
        assertThat(untouched.getIdCardNo()).isEqualTo("110101199003077890");
        assertThat(untouched.getBirthDate()).isEqualTo(LocalDate.of(1990, 3, 7));
    }

    @Test
    @DisplayName("豁免判定：角色命中 exempt_roles 为真，未命中/未登记字段/空角色清单为假")
    void exemptDecisionFollowsRoleIntersection() {
        assertThat(maskService.isExempt(List.of("DOCTOR", "ADMIN"), "idCardNo")).isTrue();
        assertThat(maskService.isExempt(List.of("NURSE"), "idCardNo")).isFalse();
        assertThat(maskService.isExempt(List.of("ADMIN"), "nonExistField")).isFalse();
        // 空角色清单（未认证请求语义）：恒不豁免
        assertThat(maskService.isExempt(List.of(), "idCardNo")).isFalse();
    }

    @Test
    @DisplayName("规则清单 VO 化：exemptRoles 拆分清单输出，空串豁免为空清单")
    void listRulesSplitsExemptRolesToList() {
        when(ruleMapper.selectList(null))
                .thenReturn(List.of(
                        rule("MASK_NAME", "name", "ADMIN, DOCTOR", true),
                        rule("MASK_BIRTH_DATE", "birthDate", "", true)));

        List<PrivacyMaskRuleVO> rules = maskService.listRules();

        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).ruleCode()).isEqualTo("MASK_NAME");
        assertThat(rules.get(0).exemptRoles()).containsExactly("ADMIN", "DOCTOR");
        // 空串=无人豁免（种子默认口径），输出空清单而非含空元素
        assertThat(rules.get(1).exemptRoles()).isEmpty();
    }

    @Test
    @DisplayName("规则维护：未知规则编码 PAT-1021 404 拒绝且不落更新")
    void updateRuleWithUnknownCodeRejected() {
        when(ruleMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() ->
                        maskService.updateRule("MASK_UNKNOWN", new PrivacyMaskRuleUpdateRequest(null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.PRIVACY_RULE_NOT_FOUND);

        verify(ruleMapper, never()).updateById(any(PrivacyMaskRule.class));
    }

    @Test
    @DisplayName("规则维护全量入参：三字段覆盖库值（保留策略/豁免扩容/停用），wrapper 锁 rule_code 等值")
    void updateRuleCoversAllProvidedFields() {
        when(ruleMapper.selectOne(any())).thenReturn(rule("MASK_MOBILE", "mobile", "ADMIN", true));

        PrivacyMaskRuleVO out = maskService.updateRule(
                "MASK_MOBILE", new PrivacyMaskRuleUpdateRequest("KEEP_3_4", "ADMIN,DOCTOR", false));

        assertThat(out.maskPattern()).isEqualTo("KEEP_3_4");
        assertThat(out.exemptRoles()).containsExactly("ADMIN", "DOCTOR");
        assertThat(out.enabled()).isFalse();
        ArgumentCaptor<PrivacyMaskRule> captor = ArgumentCaptor.forClass(PrivacyMaskRule.class);
        verify(ruleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getMaskPattern()).isEqualTo("KEEP_3_4");
        ArgumentCaptor<Wrapper<PrivacyMaskRule>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(ruleMapper).selectOne(wrapperCaptor.capture());
        // MP 3.5.17 wrapper 断言子串 contains：业务键等值条件锁定，禁绑定 SQL 全文
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("rule_code");
    }

    @Test
    @DisplayName("规则维护部分更新：空入参字段保留库值（null 语义=不变更）")
    void updateRuleKeepsStoredValuesWhenFieldsAbsent() {
        PrivacyMaskRule stored = rule("MASK_MOBILE", "mobile", "ADMIN", true);
        when(ruleMapper.selectOne(any())).thenReturn(stored);

        PrivacyMaskRuleVO out =
                maskService.updateRule("MASK_MOBILE", new PrivacyMaskRuleUpdateRequest(null, null, null));

        assertThat(out.maskPattern()).isEqualTo("KEEP");
        assertThat(out.exemptRoles()).containsExactly("ADMIN");
        assertThat(out.enabled()).isTrue();
        verify(ruleMapper).updateById(any(PrivacyMaskRule.class));
    }
}
