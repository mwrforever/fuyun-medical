package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.convert.DictConverter;
import com.fuyun.system.dto.DictItemCreateRequest;
import com.fuyun.system.entity.DictItemEntity;
import com.fuyun.system.entity.DictVersionEntity;
import com.fuyun.system.enums.DictVersionStatus;
import com.fuyun.system.mapper.DictItemMapper;
import com.fuyun.system.service.impl.DictItemServiceImpl;
import com.fuyun.system.vo.DictItemVO;
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
 * 字典条目服务单元测试（版本不可变性前置校验与缺省值语义，M01 Spec §5 + BRIEF-PR3-01 §3.2）。
 *
 * <p>覆盖：版本缺失 SYS-1012/404、非 DRAFT 版本禁改条目 SYS-1013/409、草稿版本新增成功
 * （排序号缺省 0 与显式传入两分支）。
 */
@ExtendWith(MockitoExtension.class)
class DictItemServiceImplTest {

    private static final long VERSION_ID = 8101L;

    @Mock
    private DictItemMapper dictItemMapper;

    @Mock
    private IDictVersionService dictVersionService;

    @Captor
    private ArgumentCaptor<DictItemEntity> insertEntityCaptor;

    private DictItemServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DictItemEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new DictItemServiceImpl(dictVersionService, DictConverter.INSTANCE);
        ReflectionTestUtils.setField(service, "baseMapper", dictItemMapper);
        ReflectionTestUtils.setField(service, "entityClass", DictItemEntity.class);
    }

    @Test
    @DisplayName("版本不存在：SYS-1012/404 拒绝，不产生插入")
    void addItemRejectsMissingVersion() {
        when(dictVersionService.getById(VERSION_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.addItem(VERSION_ID, new DictItemCreateRequest("M", "男", null, null)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.DICT_VERSION_NOT_FOUND);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(dictItemMapper, never()).insert(any(DictItemEntity.class));
    }

    @Test
    @DisplayName("已发布版本禁改条目：SYS-1013/409 拒绝（版本不可变性）")
    void addItemRejectsPublishedVersion() {
        when(dictVersionService.getById(VERSION_ID)).thenReturn(version(DictVersionStatus.PUBLISHED));

        assertThatThrownBy(() -> service.addItem(VERSION_ID, new DictItemCreateRequest("M", "男", null, null)))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(SystemErrorCode.DICT_VERSION_NOT_PUBLISHABLE));
        verify(dictItemMapper, never()).insert(any(DictItemEntity.class));
    }

    @Test
    @DisplayName("草稿版本新增成功：排序号缺省按 0 落库，响应组装出参")
    void addItemInsertsIntoDraftVersionWithDefaultSort() {
        when(dictVersionService.getById(VERSION_ID)).thenReturn(version(DictVersionStatus.DRAFT));
        when(dictItemMapper.insert(any(DictItemEntity.class))).thenReturn(1);

        DictItemVO vo = service.addItem(VERSION_ID, new DictItemCreateRequest("M", "男", null, null));

        verify(dictItemMapper).insert(insertEntityCaptor.capture());
        DictItemEntity saved = insertEntityCaptor.getValue();
        assertThat(saved.getDictVersionId()).isEqualTo(VERSION_ID);
        assertThat(saved.getItemCode()).isEqualTo("M");
        assertThat(saved.getItemName()).isEqualTo("男");
        assertThat(saved.getParentCode()).isNull();
        assertThat(saved.getSort()).isZero();
        assertThat(vo.itemCode()).isEqualTo("M");
        assertThat(vo.sort()).isZero();
    }

    @Test
    @DisplayName("显式排序号与父编码透传：入参非空时按入参落库")
    void addItemKeepsExplicitSortAndParentCode() {
        when(dictVersionService.getById(VERSION_ID)).thenReturn(version(DictVersionStatus.DRAFT));
        when(dictItemMapper.insert(any(DictItemEntity.class))).thenReturn(1);

        service.addItem(VERSION_ID, new DictItemCreateRequest("MARRIED", "已婚", "MARITAL", 5));

        verify(dictItemMapper).insert(insertEntityCaptor.capture());
        assertThat(insertEntityCaptor.getValue().getSort()).isEqualTo(5);
        assertThat(insertEntityCaptor.getValue().getParentCode()).isEqualTo("MARITAL");
    }

    /** 构造字典版本实体样本 */
    private DictVersionEntity version(DictVersionStatus status) {
        DictVersionEntity entity = new DictVersionEntity();
        entity.setId(VERSION_ID);
        entity.setDictTypeId(8001L);
        entity.setVersion(1);
        entity.setStatus(status);
        return entity;
    }
}
