package com.fuyun.outpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.mapper.VisitMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 诊疗关系查询 SPI 实现单测（patient/api/CareRelationQuery，Task 8）：在途三态（WAITING/
 * IN_CONSULT/PENDING_FEE）命中即 true、未命中 false——D-16 三态门禁第二道收紧的判定面。
 */
@ExtendWith(MockitoExtension.class)
class OutpatientCareRelationQueryTest {

    @Mock
    private VisitMapper visitMapper;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件列解析依赖 TableInfo（容器外单测手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Visit.class);
    }

    @Test
    @DisplayName("在途诊疗关系命中：patient×doctor×三态计数>0 即 true（第二道门禁放行）")
    void hasCareRelationReturnsTrueWhenOngoingVisitHit() {
        when(visitMapper.selectCount(any())).thenReturn(2L);

        assertThat(new OutpatientCareRelationQuery(visitMapper).hasCareRelation(9L, "9001"))
                .isTrue();
    }

    @Test
    @DisplayName("在途诊疗关系未命中：计数为 0 即 false（无豁免无关系即 403 依据）")
    void hasCareRelationReturnsFalseWithoutOngoingVisit() {
        when(visitMapper.selectCount(any())).thenReturn(0L);

        assertThat(new OutpatientCareRelationQuery(visitMapper).hasCareRelation(9L, "9001"))
                .isFalse();
    }
}
