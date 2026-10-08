package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 护理工作量统计端口实现单测（批次 2 册 2）：①在院计数直传出参；②计数 null 兜底零值视图
 * （不造数）。JaCoCo nursing.service.impl 1.00 行覆盖红线：本类承载 NursingStatsPortImpl
 * 全部分支面。
 */
@ExtendWith(MockitoExtension.class)
class NursingStatsPortImplTest {

    @Mock
    private NursingWardPatientMapper wardPatientMapper;

    private NursingStatsPortImpl service;

    @BeforeEach
    void setUp() {
        service = new NursingStatsPortImpl(wardPatientMapper);
    }

    @Test
    @DisplayName("①在院计数：投影表在册行计数直传出参")
    void inHospitalStatsReturnsCountFromMapper() {
        when(wardPatientMapper.selectCount(null)).thenReturn(86L);

        assertThat(service.inHospitalStats().inHospitalCount()).isEqualTo(86L);
    }

    @Test
    @DisplayName("②空数据零值视图：计数 null 兜底零，不造数")
    void inHospitalStatsReturnsZeroOnNullCount() {
        when(wardPatientMapper.selectCount(null)).thenReturn(null);

        assertThat(service.inHospitalStats().inHospitalCount()).isZero();
    }
}
