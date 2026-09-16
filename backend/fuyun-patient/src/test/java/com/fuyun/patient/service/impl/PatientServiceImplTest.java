package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.patient.service.IPatientService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 患者主索引服务实现装配冒烟单测：本类为 IService 空本体（CRUD 经 BaseMapper/IService 继承能力），
 * 验证默认构造可实例化且承载 IService 契约——PatientWebConfig @Import 装配路径的前提。
 */
class PatientServiceImplTest {

    @Test
    @DisplayName("默认构造可用：@Import 装配可实例化且实现 IPatientService 契约")
    void constructsAsIServiceBeanForWebConfigImport() {
        assertThat(new PatientServiceImpl()).isInstanceOf(IPatientService.class);
    }
}
