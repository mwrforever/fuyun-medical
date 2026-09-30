package com.fuyun.inpatient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 住院就诊共享访问器单测（EX-44 下沉验收面）：requireByVisitId / requireByPk 的
 * load+check 语义——命中行同引用透传（定位语义零改写）、未命中统一 IP-1007（404）；
 * by-visitId 固定文案、by-pk 参数化文案原样透传（各调用点「数据不一致」定位键不同，
 * 对外契约零变化——文案参数化面用真实调用点文案形态覆盖）。
 * MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息。
 */
@ExtendWith(MockitoExtension.class)
class InpatientVisitAccessorTest {

    /** 编排主体 I 型 14 位就诊号 */
    private static final String VISIT_ID = "I2026092500001";

    /** 就诊行表主键（关联行 visit_id 外键消费面——非 I 型号） */
    private static final long VISIT_PK = 7001L;

    @Mock
    private InpatientVisitMapper visitMapper;

    private InpatientVisitAccessor accessor;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InpatientVisit.class);
    }

    @BeforeEach
    void setUp() {
        accessor = new InpatientVisitAccessor(visitMapper);
    }

    @Test
    @DisplayName("requireByVisitId 命中：就诊号定位返回同引用行（零改写），查询经 mapper 单次取数")
    void requireByVisitIdHitsAndReturnsSameRow() {
        InpatientVisit row = visitRow();
        when(visitMapper.selectOne(any())).thenReturn(row);

        InpatientVisit result = accessor.requireByVisitId(VISIT_ID);

        // 同引用断言：访问器只做定位与守卫，不改写行内容
        assertThat(result).isSameAs(row);
        verify(visitMapper).selectOne(any());
    }

    @Test
    @DisplayName("requireByVisitId 未命中：抛 IP-1007（404），文案为统一固定形态（就诊号定位键）")
    void requireByVisitIdMissesAndThrowsVisitNotFound() {
        when(visitMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> accessor.requireByVisitId(VISIT_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(((BizException) e).getMessage()).isEqualTo("住院就诊不存在：" + VISIT_ID);
                });
    }

    @Test
    @DisplayName("requireByPk 命中：表主键定位返回同引用行（零改写），查询经 mapper 单次取数")
    void requireByPkHitsAndReturnsSameRow() {
        InpatientVisit row = visitRow();
        when(visitMapper.selectById(VISIT_PK)).thenReturn(row);

        InpatientVisit result = accessor.requireByPk(VISIT_PK, "任意未命中文案");

        assertThat(result).isSameAs(row);
        verify(visitMapper).selectById(VISIT_PK);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "医嘱关联住院就诊不存在（数据不一致）：visitId(pk)=" + VISIT_PK,
                "会诊关联住院就诊不存在（数据不一致）：visitId(pk)=" + VISIT_PK,
                "出院申请关联住院就诊不存在（数据不一致）：requestNo=DC2026092500001",
                "住院就诊不存在：visitId(pk)=" + VISIT_PK
            })
    @DisplayName("requireByPk 未命中：抛 IP-1007（404），参数化文案原样透传（各调用点定位键零变化）")
    void requireByPkMissesAndThrowsWithParameterizedMessage(String notFoundMessage) {
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);

        assertThatThrownBy(() -> accessor.requireByPk(VISIT_PK, notFoundMessage))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(((BizException) e).getMessage()).isEqualTo(notFoundMessage);
                });
    }

    /** 就诊行测试桩（ADMITTED 在院态——字段面仅承载返回语义，无业务断言依赖） */
    private InpatientVisit visitRow() {
        InpatientVisit row = new InpatientVisit();
        row.setId(VISIT_PK);
        row.setVisitId(VISIT_ID);
        row.setPatientId(1001L);
        row.setStatus("ADMITTED");
        return row;
    }
}
