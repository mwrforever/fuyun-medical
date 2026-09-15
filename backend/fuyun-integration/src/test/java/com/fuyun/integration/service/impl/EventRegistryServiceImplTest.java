package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.EventRegistryQuery;
import com.fuyun.integration.entity.EventRegistry;
import com.fuyun.integration.mapper.EventRegistryMapper;
import com.fuyun.integration.service.EventRegistrationSpec;
import com.fuyun.integration.vo.EventRegistryVO;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * event_registry 登记服务单元测试：验证事件契约登记的幂等语义与订阅登记的治理规则。
 *
 * <p>核心断言（M20 治理约定）：登记幂等（同 event_type 不覆盖已冻结契约）、订阅先登记后生效
 * （缺失/DEPRECATED 拒绝）、订阅追加幂等、broadcast 广播标记落库。mapper 以 Mockito 模拟，
 * 测试前手动装载 MP 表信息缓存（lambda 条件解析列名依赖 TableInfo，纯单测无 Spring 容器）。
 */
@ExtendWith(MockitoExtension.class)
class EventRegistryServiceImplTest {

    @Mock
    private EventRegistryMapper eventRegistryMapper;

    @Captor
    private ArgumentCaptor<Wrapper<EventRegistry>> updateWrapperCaptor;

    @Captor
    private ArgumentCaptor<EventRegistry> insertEntityCaptor;

    private EventRegistryServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 装载 EventRegistry 表信息缓存：lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EventRegistry.class);
    }

    @BeforeEach
    void setUp() {
        service = new EventRegistryServiceImpl(IntegrationConverter.INSTANCE);
        // ServiceImpl 的 baseMapper 为 protected 字段，单测经反射注入 mock（等价 Spring 容器 @Autowired 装配）；
        // MP 3.5.17 getEntityClass() 默认从 MyBatis mapper 代理反推实体类，mock 代理不可得，
        // 须直接预置 entityClass 字段短路该解析路径
        ReflectionTestUtils.setField(service, "baseMapper", eventRegistryMapper);
        ReflectionTestUtils.setField(service, "entityClass", EventRegistry.class);
    }

    @Test
    @DisplayName("register 新事件：插入台账并携带 ACTIVE 状态与订阅清单")
    void registerInsertsNewEventWithActiveStatus() {
        when(eventRegistryMapper.selectOne(any())).thenReturn(null);
        when(eventRegistryMapper.insert(any(EventRegistry.class))).thenReturn(1);

        service.register(
                new EventRegistrationSpec("system.dict.published", "system", "字典发布广播：dictType/version", "it", false));

        verify(eventRegistryMapper).insert(insertEntityCaptor.capture());
        EventRegistry saved = insertEntityCaptor.getValue();
        assertThat(saved.getEventType()).isEqualTo("system.dict.published");
        assertThat(saved.getProducerModule()).isEqualTo("system");
        assertThat(saved.getPayloadDesc()).isEqualTo("字典发布广播：dictType/version");
        assertThat(saved.getSubscriberModules()).isEqualTo("it");
        assertThat(saved.getStatus()).isEqualTo(MessagingConstants.REGISTRY_STATUS_ACTIVE);
    }

    @Test
    @DisplayName("register 重复 event_type：幂等跳过且不覆盖既有契约（不产生插入）")
    void registerSkipsDuplicateEventTypeWithoutOverwrite() {
        when(eventRegistryMapper.selectOne(any())).thenReturn(activeRow("it"));

        service.register(
                new EventRegistrationSpec("system.dict.published", "system", "另一份载荷描述（应被忽略）", "other-module", false));

        verify(eventRegistryMapper, never()).insert(any(EventRegistry.class));
    }

    @Test
    @DisplayName("register 并发冲突：insert 命中唯一索引抛 DuplicateKeyException 时按幂等语义跳过不抛出")
    void registerTreatsUniqueIndexConflictAsIdempotentSkip() {
        when(eventRegistryMapper.selectOne(any())).thenReturn(null);
        // 并发首登记场景：check-then-insert 竞态下后到者命中 uk_event_registry_event_type
        when(eventRegistryMapper.insert(any(EventRegistry.class)))
                .thenThrow(new DuplicateKeyException(
                        "duplicate key value violates unique constraint \"uk_event_registry_event_type\""));

        assertThatCode(() -> service.register(
                        new EventRegistrationSpec("system.dict.published", "system", "并发侧登记请求", "it", false)))
                .doesNotThrowAnyException();
        verify(eventRegistryMapper).insert(any(EventRegistry.class));
    }

    @Test
    @DisplayName("register broadcast=true：订阅清单落 broadcast 广播标记（零订阅广播）")
    void registerMarksBroadcastInSubscriberModules() {
        when(eventRegistryMapper.selectOne(any())).thenReturn(null);
        when(eventRegistryMapper.insert(any(EventRegistry.class))).thenReturn(1);

        service.register(new EventRegistrationSpec(
                "integration.convention.event-envelope", "integration", "CF-1 信封约定冻结", null, true));

        verify(eventRegistryMapper).insert(insertEntityCaptor.capture());
        assertThat(insertEntityCaptor.getValue().getSubscriberModules())
                .isEqualTo(MessagingConstants.SUBSCRIBER_BROADCAST);
    }

    @Test
    @DisplayName("registerSubscriber 事件缺失：抛 IllegalStateException 阻断订阅（事件先登记后订阅）")
    void registerSubscriberRejectsUnregisteredEvent() {
        when(eventRegistryMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.registerSubscriber("system.unknown.event", "it"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event_registry");
        verify(eventRegistryMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("registerSubscriber DEPRECATED 事件：抛 IllegalStateException 拒绝订阅")
    void registerSubscriberRejectsDeprecatedEvent() {
        EventRegistry deprecated = activeRow("it");
        deprecated.setStatus(MessagingConstants.REGISTRY_STATUS_DEPRECATED);
        when(eventRegistryMapper.selectOne(any())).thenReturn(deprecated);

        assertThatThrownBy(() -> service.registerSubscriber("system.dict.published", "lab"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MessagingConstants.REGISTRY_STATUS_DEPRECATED);
        verify(eventRegistryMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("registerSubscriber 新订阅模块：追加至订阅清单并写回台账")
    void registerSubscriberAppendsNewModuleToSubscriberList() {
        when(eventRegistryMapper.selectOne(any())).thenReturn(activeRow("it"));
        // CAS 条件更新（BaseMapper.update 返回 int 影响行数）：首次命中写回 1 行
        when(eventRegistryMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        service.registerSubscriber("system.dict.published", "lab");

        verify(eventRegistryMapper).update(isNull(), updateWrapperCaptor.capture());
        LambdaUpdateWrapper<EventRegistry> wrapper = asLambdaUpdateWrapper(updateWrapperCaptor.getValue());
        // 业务结果断言：写回值 = 既有清单追加新模块（追加语义，不覆盖既有订阅方）
        assertThat(wrapper.getParamNameValuePairs().values()).contains("it,lab");
    }

    @Test
    @DisplayName("registerSubscriber 重复追加：幂等跳过，不产生更新")
    void registerSubscriberSkipsDuplicateModuleIdempotently() {
        when(eventRegistryMapper.selectOne(any())).thenReturn(activeRow("it,lab"));

        service.registerSubscriber("system.dict.published", "lab");

        verify(eventRegistryMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("broadcast 拒订守卫：订阅清单为 broadcast 标记行时抛异常（零订阅广播不承载订阅清单）")
    void registerSubscriberRejectsBroadcastMarkedRow() {
        EventRegistry broadcastRow = activeRow("broadcast");
        when(eventRegistryMapper.selectOne(any())).thenReturn(broadcastRow);

        assertThatThrownBy(() -> service.registerSubscriber("integration.convention.event-envelope", "it"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broadcast");
        verify(eventRegistryMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("并发守卫：CAS 影响 0 行时重读重算重试，第二次命中后写入含两模块的清单")
    void registerSubscriberRetriesOnCasMiss() {
        EventRegistry firstRead = activeRow("it");
        EventRegistry secondRead = activeRow("it,lab");
        when(eventRegistryMapper.selectOne(any())).thenReturn(firstRead, secondRead);
        // 首次 CAS 未命中（他实例已并发改写），第二次命中
        when(eventRegistryMapper.update(isNull(), any(Wrapper.class))).thenReturn(0, 1);

        service.registerSubscriber("system.dict.published", "pharmacy");

        ArgumentCaptor<Wrapper<EventRegistry>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(eventRegistryMapper, times(2)).update(isNull(), captor.capture());
        assertThat(((LambdaUpdateWrapper<EventRegistry>) captor.getAllValues().get(1))
                        .getParamNameValuePairs()
                        .values())
                .contains("it,lab,pharmacy");
    }

    @Test
    @DisplayName("并发守卫上界：CAS 连续 3 次未命中即 fail-fast（禁静默丢订阅）")
    void registerSubscriberFailsFastAfterCasAttemptsExhausted() {
        when(eventRegistryMapper.selectOne(any())).thenReturn(activeRow("it"));
        when(eventRegistryMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.registerSubscriber("system.dict.published", "pharmacy"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("并发");
        verify(eventRegistryMapper, times(3)).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("契约台账查询：0 基分页契约与订阅读数出参")
    void queryReturnsPagedRegistryRows() {
        EventRegistry row = activeRow("it,lab");
        when(eventRegistryMapper.selectPage(any(IPage.class), any(Wrapper.class)))
                .thenAnswer(invocation -> {
                    IPage<EventRegistry> page = invocation.getArgument(0);
                    page.setRecords(List.of(row));
                    page.setTotal(1L);
                    return page;
                });

        PageResult<EventRegistryVO> result = service.query(new EventRegistryQuery(null, "system", "ACTIVE", 0, 20));

        assertThat(result.page()).isZero();
        assertThat(result.content().get(0).subscriberModules()).isEqualTo("it,lab");
        assertThat(result.content().get(0).eventType()).isEqualTo("system.dict.published");
    }

    @Test
    @DisplayName("isRegistered：已登记返回 true，未登记返回 false")
    void isRegisteredReflectsRegistryExistence() {
        when(eventRegistryMapper.selectCount(any())).thenReturn(1L);
        assertThat(service.isRegistered("system.dict.published")).isTrue();

        when(eventRegistryMapper.selectCount(any())).thenReturn(0L);
        assertThat(service.isRegistered("system.unknown.event")).isFalse();
    }

    /**
     * 构造 ACTIVE 状态的台账行样本。
     *
     * @param subscriberModules 订阅模块清单（逗号分隔）
     * @return 事件台账实体样本
     */
    private EventRegistry activeRow(String subscriberModules) {
        EventRegistry row = new EventRegistry();
        row.setId(1L);
        row.setEventType("system.dict.published");
        row.setProducerModule("system");
        row.setPayloadDesc("字典发布广播：dictType/version");
        row.setSubscriberModules(subscriberModules);
        row.setStatus(MessagingConstants.REGISTRY_STATUS_ACTIVE);
        return row;
    }

    /**
     * 将捕获的 Wrapper 断言为 LambdaUpdateWrapper 以读取写回参数。
     *
     * @param wrapper mapper 捕获到的更新条件
     * @return lambda 更新包装器
     */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<EventRegistry> asLambdaUpdateWrapper(Wrapper<EventRegistry> wrapper) {
        return (LambdaUpdateWrapper<EventRegistry>) wrapper;
    }
}
