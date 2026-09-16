package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.patient.dto.PatientMatchCheckRequest;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.properties.PatientEmpiProperties;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.vo.PatientMatchCheckVO;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * EMPI 分层匹配引擎单测（M02 §3.1/§10 测试要点）：强标识唯一命中归一、强标识命中属性矛盾降级待审、
 * 弱标识评分达阈值待审、低于阈值新建、无标识仅姓名低分新建五类，另补 NAME_MOBILE/NAME_SEX 评分
 * 分支与出生日期双空一致性边界（核心链路 100% 行覆盖口径）。
 */
@ExtendWith(MockitoExtension.class)
class PatientMatchingServiceImplTest {

    @Mock
    private IPatientService patientService;

    @Mock
    private PatientFieldCrypto crypto;

    /** 被测引擎候选查询经本模块 IService 主表（A.4.3-13），mock 直接生效 */
    private PatientMatchingServiceImpl matchingService;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件列名解析依赖 TableInfo（容器外单测需手动初始化一次，MessageIdempotencyServiceImplTest 同款）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Patient.class);
    }

    @BeforeEach
    void setUp() {
        matchingService = new PatientMatchingServiceImpl(patientService, crypto, new PatientEmpiProperties(85));
        lenient().when(crypto.hash(anyString())).thenAnswer(inv -> "h_" + inv.getArgument(0, String.class));
    }

    private Patient hit(long id, String name, String sex, String birth, String mobileHash) {
        Patient p = new Patient();
        p.setPatientId(id);
        p.setName(name);
        p.setSex(sex);
        p.setBirthDate(LocalDate.parse(birth));
        p.setMobileHash(mobileHash);
        return p;
    }

    @Test
    @DisplayName("强标识唯一命中且姓名性别出生日期一致 → AUTO_MATCH 归一既有档")
    void strongIdentifierExactHitWithConsistentAttributesAutoMatches() {
        when(patientService.list(ArgumentMatchers.<Wrapper<Patient>>any()))
                .thenReturn(List.of(hit(1L, "张三", "1", "1990-03-07", null)));
        PatientMatchCheckVO vo = matchingService.preCheck(
                new PatientMatchCheckRequest("张三", "1", "1990-03-07", "110101199003077890", null));
        assertThat(vo.outcome()).isEqualTo("AUTO_MATCH");
        assertThat(vo.candidatePatientId()).isEqualTo(1L);
        assertThat(vo.matchedRules()).containsExactly("ID_CARD_EXACT");
        // 强标识查询口径：按身份证盲索引列等值查（getSqlSegment 子串断言，禁全文精确比对）
        ArgumentCaptor<Wrapper<Patient>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(patientService).list(wrapperCaptor.capture());
        LambdaQueryWrapper<Patient> wrapper = (LambdaQueryWrapper<Patient>) wrapperCaptor.getValue();
        // 先渲染 SQL 片段：MP 条件参数在 getSqlSegment 惰性求值时才写入 paramNameValuePairs（3.5.17 实测）
        assertThat(wrapper.getSqlSegment()).contains("id_card_no_hash");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("h_110101199003077890");
    }

    @Test
    @DisplayName("强标识命中但属性矛盾 → SUSPECT 固定满分待审（不自动合并，红线安全边界）")
    void strongIdentifierHitWithConflictingAttributesBecomesSuspect() {
        when(patientService.list(ArgumentMatchers.<Wrapper<Patient>>any()))
                .thenReturn(List.of(hit(2L, "李四", "2", "1985-01-01", null)));
        PatientMatchCheckVO vo = matchingService.preCheck(
                new PatientMatchCheckRequest("张三", "1", "1990-03-07", "110101199003077890", null));
        assertThat(vo.outcome()).isEqualTo("SUSPECT");
        assertThat(vo.candidatePatientId()).isEqualTo(2L);
        assertThat(vo.score().intValue()).isEqualTo(100);
        assertThat(vo.matchedRules()).containsExactly("ID_CARD_CONFLICT");
    }

    @Test
    @DisplayName("弱标识姓名+性别+出生日期命中评分 95 ≥ 阈值 85 → SUSPECT 待审")
    void weakIdentifierHighScoreBecomesSuspect() {
        when(patientService.list(ArgumentMatchers.<Wrapper<Patient>>any()))
                .thenReturn(List.of(hit(3L, "张三", "1", "1990-03-07", null)));
        PatientMatchCheckVO vo =
                matchingService.preCheck(new PatientMatchCheckRequest("张三", "1", "1990-03-07", null, null));
        assertThat(vo.outcome()).isEqualTo("SUSPECT");
        assertThat(vo.score().intValue()).isEqualTo(95);
    }

    @Test
    @DisplayName("弱标识仅姓名命中评分 60 < 阈值 → NO_MATCH 直接新建")
    void weakIdentifierLowScoreCreatesNew() {
        when(patientService.list(ArgumentMatchers.<Wrapper<Patient>>any()))
                .thenReturn(List.of(hit(4L, "张三", "2", "2000-01-01", null)));
        PatientMatchCheckVO vo =
                matchingService.preCheck(new PatientMatchCheckRequest("张三", "1", "1990-03-07", null, null));
        assertThat(vo.outcome()).isEqualTo("NO_MATCH");
        assertThat(vo.candidatePatientId()).isNull();
    }

    @Test
    @DisplayName("无任何候选（同名无人）→ NO_MATCH 且零评分空规则")
    void noCandidateYieldsNoMatch() {
        when(patientService.list(ArgumentMatchers.<Wrapper<Patient>>any())).thenReturn(List.of());
        PatientMatchCheckVO vo =
                matchingService.preCheck(new PatientMatchCheckRequest("王五", "1", "1991-05-05", null, null));
        assertThat(vo.outcome()).isEqualTo("NO_MATCH");
        assertThat(vo.score()).isNull();
        assertThat(vo.matchedRules()).isEmpty();
        // 弱标识候选集口径：同名等值查
        ArgumentCaptor<Wrapper<Patient>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(patientService).list(wrapperCaptor.capture());
        LambdaQueryWrapper<Patient> wrapper = (LambdaQueryWrapper<Patient>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("name");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("王五");
    }

    @Test
    @DisplayName("弱标识姓名+手机号命中评分 90 ≥ 阈值 → SUSPECT 待审（NAME_MOBILE 分支）")
    void nameMobileHitReachesSuspectThreshold() {
        when(patientService.list(ArgumentMatchers.<Wrapper<Patient>>any()))
                .thenReturn(List.of(hit(5L, "张三", "2", "2000-01-01", "h_13800001234")));
        PatientMatchCheckVO vo =
                matchingService.preCheck(new PatientMatchCheckRequest("张三", "1", "1990-03-07", null, "13800001234"));
        assertThat(vo.outcome()).isEqualTo("SUSPECT");
        assertThat(vo.score().intValue()).isEqualTo(90);
        assertThat(vo.matchedRules()).containsExactly("NAME_MOBILE");
    }

    @Test
    @DisplayName("弱标识姓名+性别命中评分 70 < 阈值 → NO_MATCH 新建（NAME_SEX 分支）")
    void nameSexOnlyBelowThresholdCreatesNew() {
        when(patientService.list(ArgumentMatchers.<Wrapper<Patient>>any()))
                .thenReturn(List.of(hit(6L, "张三", "1", "2000-01-01", null)));
        PatientMatchCheckVO vo =
                matchingService.preCheck(new PatientMatchCheckRequest("张三", "1", "1990-03-07", null, null));
        assertThat(vo.outcome()).isEqualTo("NO_MATCH");
        assertThat(vo.candidatePatientId()).isNull();
    }

    @Test
    @DisplayName("候选与请求出生日期均为空视为一致 → 姓名+性别+出生 95 分 SUSPECT（边界一致性口径）")
    void bothBirthDatesNullTreatedAsConsistent() {
        Patient anonymous = new Patient();
        anonymous.setPatientId(7L);
        anonymous.setName("无名氏");
        anonymous.setSex("1");
        anonymous.setBirthDate(null);
        when(patientService.list(ArgumentMatchers.<Wrapper<Patient>>any())).thenReturn(List.of(anonymous));
        PatientMatchCheckVO vo = matchingService.preCheck(new PatientMatchCheckRequest("无名氏", "1", null, null, null));
        assertThat(vo.outcome()).isEqualTo("SUSPECT");
        assertThat(vo.score().intValue()).isEqualTo(95);
        assertThat(vo.matchedRules()).containsExactly("NAME_SEX_BIRTH");
    }
}
