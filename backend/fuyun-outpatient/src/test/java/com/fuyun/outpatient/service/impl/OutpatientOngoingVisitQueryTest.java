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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 「在途就诊查询」SPI 实现单测（patient/api OngoingVisitQuery 冻结语义的 M03 侧注册面）：锚定
 * 「在途态命中即 true（合并阻断收紧）」与「无在途态返回 false（放行）」两分支——在途态全集谓词
 * 由实现常量承载，命中判定以 selectCount 计数为锚。
 */
@ExtendWith(MockitoExtension.class)
class OutpatientOngoingVisitQueryTest {

    @Mock
    private VisitMapper visitMapper;

    private OutpatientOngoingVisitQuery query;

    @BeforeAll
    static void initTableInfo() {
        // 在途态计数查询的 lambda 条件列解析依赖 TableInfo（容器外单测手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Visit.class);
    }

    @BeforeEach
    void setUp() {
        query = new OutpatientOngoingVisitQuery(visitMapper);
    }

    @Test
    @DisplayName("hasOngoingVisit：在途态（REGISTERED/WAITING/IN_CONSULT/PENDING_FEE）命中返回 true")
    void hasOngoingVisitReturnsTrueWhenOngoingExists() {
        when(visitMapper.selectCount(any())).thenReturn(1L);

        assertThat(query.hasOngoingVisit(9L)).isTrue();
    }

    @Test
    @DisplayName("hasOngoingVisit：无在途就诊返回 false（合并前置检查放行）")
    void hasOngoingVisitReturnsFalseWhenNoneExists() {
        when(visitMapper.selectCount(any())).thenReturn(0L);

        assertThat(query.hasOngoingVisit(9L)).isFalse();
    }
}
