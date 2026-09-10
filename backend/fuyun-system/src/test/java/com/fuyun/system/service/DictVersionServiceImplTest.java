package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.convert.DictConverter;
import com.fuyun.system.entity.DictTypeEntity;
import com.fuyun.system.entity.DictVersionEntity;
import com.fuyun.system.enums.DictVersionStatus;
import com.fuyun.system.internal.DictVersionPublishedEvent;
import com.fuyun.system.mapper.DictVersionMapper;
import com.fuyun.system.service.impl.DictVersionServiceImpl;
import com.fuyun.system.vo.DictVersionVO;
import java.time.OffsetDateTime;
import java.util.List;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 字典版本服务单元测试（发布状态机与并发防双广播，M01 Spec §5 + BRIEF-PR3-01 §3.2）。
 *
 * <p>覆盖：创建（类型缺失 SYS-1011、首版本从 1 起、版本号自增）、发布状态机（版本缺失
 * SYS-1012、非 DRAFT/竞态落败经条件更新影响行数 0 拒绝 SYS-1013 且不发事件、发布成功置
 * PUBLISHED + published_at/effective_at、同类型旧 PUBLISHED 置 DEPRECATED、事务内发布
 * Spring 应用事件且事件字段正确）。mapper/事件发布器以 Mockito 模拟（AFTER_COMMIT 的
 * MQ 发送时机归 SystemEventPublisherTest 与集成测试验证）。
 */
@ExtendWith(MockitoExtension.class)
class DictVersionServiceImplTest {

    private static final long TYPE_ID = 8001L;

    private static final String TYPE_CODE = "gender";

    @Mock
    private DictVersionMapper dictVersionMapper;

    @Mock
    private IDictTypeService dictTypeService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Captor
    private ArgumentCaptor<DictVersionEntity> insertEntityCaptor;

    @Captor
    private ArgumentCaptor<DictVersionPublishedEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<DictVersionEntity>> updateWrapperCaptor;

    private DictVersionServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), DictVersionEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new DictVersionServiceImpl(dictTypeService, DictConverter.INSTANCE, eventPublisher);
        ReflectionTestUtils.setField(service, "baseMapper", dictVersionMapper);
        ReflectionTestUtils.setField(service, "entityClass", DictVersionEntity.class);
    }

    @Test
    @DisplayName("创建版本：类型不存在按 SYS-1011/404 拒绝")
    void createVersionRejectsMissingType() {
        when(dictTypeService.getByTypeCode(TYPE_CODE)).thenReturn(null);

        assertThatThrownBy(() -> service.createVersion(TYPE_CODE)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.DICT_TYPE_NOT_FOUND);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
    }

    @Test
    @DisplayName("创建首版本：无既有版本时版本号从 1 起，默认 DRAFT，条目为空清单")
    void createVersionStartsAtOneForNewType() {
        when(dictTypeService.getByTypeCode(TYPE_CODE)).thenReturn(type());
        when(dictVersionMapper.selectList(any())).thenReturn(List.of());
        when(dictVersionMapper.insert(any(DictVersionEntity.class))).thenReturn(1);

        DictVersionVO vo = service.createVersion(TYPE_CODE);

        verify(dictVersionMapper).insert(insertEntityCaptor.capture());
        assertThat(insertEntityCaptor.getValue().getVersion()).isEqualTo(1);
        assertThat(insertEntityCaptor.getValue().getStatus()).isEqualTo(DictVersionStatus.DRAFT);
        assertThat(vo.typeCode()).isEqualTo(TYPE_CODE);
        assertThat(vo.version()).isEqualTo(1);
        assertThat(vo.status()).isEqualTo(DictVersionStatus.DRAFT);
        assertThat(vo.items()).isEmpty();
    }

    @Test
    @DisplayName("创建版本号自增：既有最大版本 2 时新版本为 3")
    void createVersionIncrementsFromExistingMax() {
        when(dictTypeService.getByTypeCode(TYPE_CODE)).thenReturn(type());
        DictVersionEntity v1 = version(1, DictVersionStatus.DEPRECATED);
        DictVersionEntity v2 = version(2, DictVersionStatus.PUBLISHED);
        when(dictVersionMapper.selectList(any())).thenReturn(List.of(v1, v2));
        when(dictVersionMapper.insert(any(DictVersionEntity.class))).thenReturn(1);

        service.createVersion(TYPE_CODE);

        verify(dictVersionMapper).insert(insertEntityCaptor.capture());
        assertThat(insertEntityCaptor.getValue().getVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("发布：版本不存在按 SYS-1012/404 拒绝")
    void publishRejectsMissingVersion() {
        when(dictVersionMapper.selectById(77L)).thenReturn(null);

        assertThatThrownBy(() -> service.publish(77L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.DICT_VERSION_NOT_FOUND);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
    }

    @Test
    @DisplayName("发布：非 DRAFT 状态按 SYS-1013/409 拒绝（重复发布/废弃版本不可发布），条件更新影响行数 0 不发事件")
    void publishRejectsNonDraftVersion() {
        when(dictVersionMapper.selectById(77L)).thenReturn(version(1, DictVersionStatus.PUBLISHED));
        when(dictTypeService.getById(TYPE_ID)).thenReturn(type());
        // 条件更新（WHERE status='DRAFT'）影响行数 0：已发布版本无可更新行
        when(dictVersionMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.publish(77L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.DICT_VERSION_NOT_PUBLISHABLE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("发布：并发双 publish 竞态落败（读时 DRAFT、条件更新影响行数 0）按 SYS-1013 拒绝且不发事件")
    void publishLosesRaceKeepsSingleBroadcast() {
        // 读-检-写在并发下失效的场景：读取时仍为 DRAFT，条件更新被先行者抢占（影响行数 0）
        when(dictVersionMapper.selectById(77L)).thenReturn(version(2, DictVersionStatus.DRAFT));
        when(dictTypeService.getById(TYPE_ID)).thenReturn(type());
        when(dictVersionMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.publish(77L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.DICT_VERSION_NOT_PUBLISHABLE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        // 竞态落败方不得触达广播：仅条件更新成功（影响行数 1）者发布事务内事件
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("发布成功：条件更新置 PUBLISHED + 发布/生效时刻，事务内发布应用事件且事件字段正确")
    void publishTransitionsDraftToPublishedAndEmitsEvent() {
        when(dictVersionMapper.selectById(77L)).thenReturn(version(2, DictVersionStatus.DRAFT));
        when(dictTypeService.getById(TYPE_ID)).thenReturn(type());
        when(dictVersionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.publish(77L);

        // 两次条件更新：① 本版本 DRAFT→PUBLISHED（原子抢占发布权）② 同类型旧 PUBLISHED 置 DEPRECATED
        verify(dictVersionMapper, times(2)).update(isNull(), updateWrapperCaptor.capture());
        LambdaUpdateWrapper<DictVersionEntity> publishWrapper =
                asLambdaUpdateWrapper(updateWrapperCaptor.getAllValues().get(0));
        // 渲染触发参数回填（MP 3.5.17 查询侧语义）：条件含 id 与 DRAFT、写回含 PUBLISHED 与双时刻
        publishWrapper.getSqlSegment();
        assertThat(publishWrapper.getParamNameValuePairs().values())
                .contains(77L, DictVersionStatus.DRAFT, DictVersionStatus.PUBLISHED);
        assertThat(publishWrapper.getParamNameValuePairs().values())
                .filteredOn(OffsetDateTime.class::isInstance)
                .hasSize(2);

        // 事务上下文内发布 Spring 应用事件（AFTER_COMMIT 监听者在事务提交后发 MQ）
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().typeCode()).isEqualTo(TYPE_CODE);
        assertThat(eventCaptor.getValue().version()).isEqualTo(2);
    }

    @Test
    @DisplayName("发布：所属类型缺失按 SYS-1011/404 拒绝（数据一致性防线，不发布事件）")
    void publishRejectsMissingTypeRow() {
        when(dictVersionMapper.selectById(77L)).thenReturn(version(1, DictVersionStatus.DRAFT));
        when(dictTypeService.getById(TYPE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.publish(77L))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(SystemErrorCode.DICT_TYPE_NOT_FOUND));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("旧版本废弃条件携带排除自身：第二次条件更新含 PUBLISHED 过滤与 DEPRECATED 写回（渲染后参数核对）")
    void publishDeprecatesOldPublishedVersionsExcludingSelf() {
        when(dictVersionMapper.selectById(77L)).thenReturn(version(2, DictVersionStatus.DRAFT));
        when(dictTypeService.getById(TYPE_ID)).thenReturn(type());
        when(dictVersionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.publish(77L);

        // 第二次条件更新 = 同类型旧 PUBLISHED 置 DEPRECATED（排除自身 id=77）
        verify(dictVersionMapper, times(2)).update(isNull(), updateWrapperCaptor.capture());
        LambdaUpdateWrapper<DictVersionEntity> deprecateWrapper =
                asLambdaUpdateWrapper(updateWrapperCaptor.getAllValues().get(1));
        // 渲染触发参数回填（MP 3.5.17 查询侧语义）：条件含 PUBLISHED 与排除自身 id=77、写回 DEPRECATED
        deprecateWrapper.getSqlSegment();
        assertThat(deprecateWrapper.getParamNameValuePairs().values())
                .contains(DictVersionStatus.PUBLISHED, DictVersionStatus.DEPRECATED, 77L);
    }

    /** 构造字典类型实体样本 */
    private DictTypeEntity type() {
        DictTypeEntity type = new DictTypeEntity();
        type.setId(TYPE_ID);
        type.setTypeCode(TYPE_CODE);
        return type;
    }

    /** 构造字典版本实体样本（所属类型固定，便于断言） */
    private DictVersionEntity version(int version, DictVersionStatus status) {
        DictVersionEntity entity = new DictVersionEntity();
        entity.setId(77L);
        entity.setDictTypeId(TYPE_ID);
        entity.setVersion(version);
        entity.setStatus(status);
        entity.setPublishedAt(status == DictVersionStatus.DRAFT ? null : OffsetDateTime.now());
        return entity;
    }

    /** 将捕获的 Wrapper 断言为 LambdaUpdateWrapper 以读取写回参数 */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<DictVersionEntity> asLambdaUpdateWrapper(Wrapper<DictVersionEntity> wrapper) {
        return (LambdaUpdateWrapper<DictVersionEntity>) wrapper;
    }
}
