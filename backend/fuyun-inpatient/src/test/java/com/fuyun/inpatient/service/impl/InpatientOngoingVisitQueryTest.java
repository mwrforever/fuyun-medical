package com.fuyun.inpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 在途就诊查询 SPI 单测（patient 合并前置检查的 M04 侧口径）：在院三态
 * （REGISTERED/ADMITTED/DISCHARGE_REQUESTED）任一行存在即阻断合并；终态/无行放行。
 * MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息。
 */
@ExtendWith(MockitoExtension.class)
class InpatientOngoingVisitQueryTest {

    /** 患者主索引 */
    private static final long PATIENT_ID = 1001L;

    @Mock
    private InpatientVisitMapper visitMapper;

    @Captor
    private ArgumentCaptor<Wrapper<InpatientVisit>> queryCaptor;

    private InpatientOngoingVisitQuery query;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InpatientVisit.class);
    }

    @BeforeEach
    void setUp() {
        query = new InpatientOngoingVisitQuery(visitMapper);
    }

    @Test
    @DisplayName("在院三态行存在即阻断合并：计数>0 返回 true，谓词钉死三态词表")
    void hasOngoingVisitReturnsTrueWhenActiveRowExists() {
        when(visitMapper.selectCount(any())).thenReturn(2L);

        assertThat(query.hasOngoingVisit(PATIENT_ID)).isTrue();

        verify(visitMapper).selectCount(queryCaptor.capture());
        LambdaQueryWrapper<InpatientVisit> wrapper = rendered(queryCaptor.getValue());
        // 谓词根因锚：patient_id + 在院三态 IN（DISCHARGED/CANCELLED 终态行不计入在途面）
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(
                        PATIENT_ID,
                        VisitStatus.REGISTERED.getCode(),
                        VisitStatus.ADMITTED.getCode(),
                        VisitStatus.DISCHARGE_REQUESTED.getCode());
    }

    @Test
    @DisplayName("无在院行放行：计数=0 返回 false（终态行由三态 IN 谓词滤除）")
    void hasOngoingVisitReturnsFalseWhenNoActiveRow() {
        when(visitMapper.selectCount(any())).thenReturn(0L);

        assertThat(query.hasOngoingVisit(PATIENT_ID)).isFalse();
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表）。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<InpatientVisit> rendered(Wrapper<InpatientVisit> captured) {
        LambdaQueryWrapper<InpatientVisit> wrapper = (LambdaQueryWrapper<InpatientVisit>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }
}
