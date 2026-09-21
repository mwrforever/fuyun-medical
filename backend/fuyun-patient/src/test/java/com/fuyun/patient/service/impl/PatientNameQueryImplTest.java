package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.patient.api.PatientDisplayName;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.mapper.PatientMapper;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 患者脱敏展示名查询实现单测（M03 队列脱敏姓名出网读取面）：锚定「批量命中→掩码收口出网」
 * （姓名原文不出模块——displayName 必为打星形态）与「空入参零查询」两条语义；无命中 id 不入
 * 结果集由 in 查询天然保证。
 */
@ExtendWith(MockitoExtension.class)
class PatientNameQueryImplTest {

    @Mock
    private PatientMapper patientMapper;

    private PatientNameQueryImpl query;

    @BeforeAll
    static void initTableInfo() {
        // in 条件与 select 投影的 lambda 列解析依赖 TableInfo（容器外单测手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Patient.class);
    }

    @BeforeEach
    void setUp() {
        query = new PatientNameQueryImpl(patientMapper);
    }

    @Test
    @DisplayName("批量命中：多患者姓名掩码收口（张三→张*）——displayName 不含姓名原文，原文不出模块")
    void displayNamesMasksNamesBeforeLeavingModule() {
        Patient zhang = new Patient();
        zhang.setPatientId(9L);
        zhang.setName("张三");
        Patient li = new Patient();
        li.setPatientId(10L);
        li.setName("李四");
        when(patientMapper.selectList(any())).thenReturn(List.of(zhang, li));

        List<PatientDisplayName> result = query.displayNamesOf(List.of(9L, 10L));

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(PatientDisplayName::displayName).collect(Collectors.toList()))
                .containsExactly("张*", "李*");
        assertThat(result.stream().map(PatientDisplayName::patientId).collect(Collectors.toList()))
                .containsExactly(9L, 10L);
    }

    @Test
    @DisplayName("空入参：直接返回空列表且零查询（队列无票等高频小批量路径不落空 in）")
    void emptyInputSkipsQueryAndReturnsEmptyList() {
        assertThat(query.displayNamesOf(List.of())).isEmpty();
        verifyNoInteractions(patientMapper);
    }
}
