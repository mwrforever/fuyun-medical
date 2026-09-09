package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.convert.DictConverter;
import com.fuyun.system.dto.DictTypeCreateRequest;
import com.fuyun.system.entity.DictTypeEntity;
import com.fuyun.system.mapper.DictTypeMapper;
import com.fuyun.system.service.impl.DictTypeServiceImpl;
import com.fuyun.system.vo.DictTypeVO;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 字典类型服务单元测试（创建唯一性校验与缺省值语义，BRIEF-PR3-01 §3.2）。
 *
 * <p>覆盖：创建成功（编码/名称/国标标记落库，响应携带雪花 ID）、国标标记缺省按 false、
 * 编码重复 SYS-1014/409 拒绝且不落库、按编码查询透传。mapper 以 Mockito 模拟。
 */
@ExtendWith(MockitoExtension.class)
class DictTypeServiceImplTest {

    @Mock
    private DictTypeMapper dictTypeMapper;

    @Captor
    private ArgumentCaptor<DictTypeEntity> insertEntityCaptor;

    private DictTypeServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DictTypeEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new DictTypeServiceImpl(DictConverter.INSTANCE);
        ReflectionTestUtils.setField(service, "baseMapper", dictTypeMapper);
        ReflectionTestUtils.setField(service, "entityClass", DictTypeEntity.class);
    }

    @Test
    @DisplayName("创建成功：编码/名称/国标标记落库，响应组装出参（ID 为落库回填值）")
    void createTypeInsertsEntityAndReturnsVo() {
        when(dictTypeMapper.selectCount(any())).thenReturn(0L);
        when(dictTypeMapper.insert(any(DictTypeEntity.class))).thenAnswer(invocation -> {
            DictTypeEntity entity = invocation.getArgument(0);
            entity.setId(9001L);
            return 1;
        });

        DictTypeVO vo = service.createType(new DictTypeCreateRequest("gender", "性别字典", true, "国标 GB/T 2261.1"));

        assertThat(vo.id()).isEqualTo(9001L);
        assertThat(vo.typeCode()).isEqualTo("gender");
        assertThat(vo.typeName()).isEqualTo("性别字典");
        assertThat(vo.nationalStandard()).isTrue();
        assertThat(vo.remark()).isEqualTo("国标 GB/T 2261.1");
        verify(dictTypeMapper).insert(insertEntityCaptor.capture());
        assertThat(insertEntityCaptor.getValue().getNationalStandard()).isTrue();
    }

    @Test
    @DisplayName("国标标记缺省：nationalStandard 入参为 null 时按 false 落库（可空语义收口）")
    void createTypeDefaultsNationalStandardToFalse() {
        when(dictTypeMapper.selectCount(any())).thenReturn(0L);
        when(dictTypeMapper.insert(any(DictTypeEntity.class))).thenReturn(1);

        service.createType(new DictTypeCreateRequest("dept.type", "科室类型", null, null));

        verify(dictTypeMapper).insert(insertEntityCaptor.capture());
        assertThat(insertEntityCaptor.getValue().getNationalStandard()).isFalse();
    }

    @Test
    @DisplayName("编码重复拒绝：SYS-1014/409，不产生插入")
    void createTypeRejectsDuplicateTypeCode() {
        when(dictTypeMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.createType(new DictTypeCreateRequest("gender", "性别字典", null, null)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.DICT_TYPE_CODE_EXISTS);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(dictTypeMapper, org.mockito.Mockito.never()).insert(any(DictTypeEntity.class));
    }

    @Test
    @DisplayName("按编码查询：命中返回实体，未命中返回 null")
    void getByTypeCodeReturnsEntityOrNull() {
        DictTypeEntity expected = new DictTypeEntity();
        expected.setId(9001L);
        expected.setTypeCode("gender");
        when(dictTypeMapper.selectOne(any())).thenReturn(expected);

        assertThat(service.getByTypeCode("gender")).isSameAs(expected);

        when(dictTypeMapper.selectOne(any())).thenReturn(null);
        assertThat(service.getByTypeCode("missing")).isNull();
    }
}
