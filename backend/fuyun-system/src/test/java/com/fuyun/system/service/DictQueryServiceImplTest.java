package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.convert.DictConverter;
import com.fuyun.system.entity.DictItemEntity;
import com.fuyun.system.entity.DictTypeEntity;
import com.fuyun.system.entity.DictVersionEntity;
import com.fuyun.system.enums.DictVersionStatus;
import com.fuyun.system.mapper.DictItemMapper;
import com.fuyun.system.mapper.DictTypeMapper;
import com.fuyun.system.mapper.DictVersionMapper;
import com.fuyun.system.service.impl.DictQueryServiceImpl;
import com.fuyun.system.vo.DictVersionVO;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 字典契约型读服务单元测试（版本解析与条目全量返回，BRIEF-PR3-01 §3.2）。
 *
 * <p>覆盖：类型缺失 SYS-1011/404、无已发布版本 SYS-1012/404、version 空=当前 PUBLISHED、
 * version 显式=指定版本（任意状态）、条目清单全量返回。mapper 以 Mockito 模拟
 * （读接口豁免分页的契约理由见 IDictQueryService javadoc）。
 */
@ExtendWith(MockitoExtension.class)
class DictQueryServiceImplTest {

    private static final long TYPE_ID = 8001L;

    private static final long VERSION_ID = 8101L;

    private static final String TYPE_CODE = "gender";

    @Mock
    private DictTypeMapper dictTypeMapper;

    @Mock
    private DictVersionMapper dictVersionMapper;

    @Mock
    private DictItemMapper dictItemMapper;

    private DictQueryServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 聚合查询 lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DictTypeEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), DictVersionEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DictItemEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new DictQueryServiceImpl(dictTypeMapper, dictVersionMapper, dictItemMapper, DictConverter.INSTANCE);
    }

    @Test
    @DisplayName("类型不存在：SYS-1011/404 拒绝")
    void readPublishedRejectsMissingType() {
        when(dictTypeMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.readPublished(TYPE_CODE, null))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.DICT_TYPE_NOT_FOUND);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("version 缺省：取当前 PUBLISHED 版本并返回条目全量清单")
    void readPublishedResolvesCurrentPublishedVersionWhenVersionAbsent() {
        when(dictTypeMapper.selectOne(any())).thenReturn(type());
        when(dictVersionMapper.selectOne(any())).thenReturn(publishedVersion());
        when(dictItemMapper.selectList(any())).thenReturn(List.of(item("M", "男", 1), item("F", "女", 2)));

        DictVersionVO vo = service.readPublished(TYPE_CODE, null);

        assertThat(vo.typeCode()).isEqualTo(TYPE_CODE);
        assertThat(vo.version()).isEqualTo(2);
        assertThat(vo.status()).isEqualTo(DictVersionStatus.PUBLISHED);
        assertThat(vo.publishedAt()).isNotNull();
        assertThat(vo.items()).hasSize(2);
        assertThat(vo.items().get(0).itemCode()).isEqualTo("M");
        assertThat(vo.items().get(1).itemCode()).isEqualTo("F");
    }

    @Test
    @DisplayName("version 显式：取指定版本（任意状态，预览/回溯口径）")
    void readPublishedResolvesExplicitVersionRegardlessOfStatus() {
        when(dictTypeMapper.selectOne(any())).thenReturn(type());
        DictVersionEntity draft = publishedVersion();
        draft.setStatus(DictVersionStatus.DRAFT);
        draft.setPublishedAt(null);
        when(dictVersionMapper.selectOne(any())).thenReturn(draft);
        when(dictItemMapper.selectList(any())).thenReturn(List.of(item("M", "男", 0)));

        DictVersionVO vo = service.readPublished(TYPE_CODE, 3);

        assertThat(vo.version()).isEqualTo(2);
        assertThat(vo.status()).isEqualTo(DictVersionStatus.DRAFT);
        assertThat(vo.publishedAt()).isNull();
        assertThat(vo.items()).hasSize(1);
    }

    @Test
    @DisplayName("版本不存在（含无已发布版本）：SYS-1012/404 拒绝")
    void readPublishedRejectsMissingVersion() {
        when(dictTypeMapper.selectOne(any())).thenReturn(type());
        when(dictVersionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.readPublished(TYPE_CODE, null))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(SystemErrorCode.DICT_VERSION_NOT_FOUND));
    }

    /** 构造字典类型实体样本 */
    private DictTypeEntity type() {
        DictTypeEntity type = new DictTypeEntity();
        type.setId(TYPE_ID);
        type.setTypeCode(TYPE_CODE);
        return type;
    }

    /** 构造 PUBLISHED 版本实体样本 */
    private DictVersionEntity publishedVersion() {
        DictVersionEntity entity = new DictVersionEntity();
        entity.setId(VERSION_ID);
        entity.setDictTypeId(TYPE_ID);
        entity.setVersion(2);
        entity.setStatus(DictVersionStatus.PUBLISHED);
        entity.setPublishedAt(OffsetDateTime.now());
        return entity;
    }

    /** 构造字典条目实体样本 */
    private DictItemEntity item(String itemCode, String itemName, int sort) {
        DictItemEntity item = new DictItemEntity();
        item.setDictVersionId(VERSION_ID);
        item.setItemCode(itemCode);
        item.setItemName(itemName);
        item.setSort(sort);
        return item;
    }
}
