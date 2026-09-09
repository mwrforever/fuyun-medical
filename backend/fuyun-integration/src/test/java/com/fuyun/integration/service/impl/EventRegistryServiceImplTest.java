package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.EventRegistry;
import com.fuyun.integration.mapper.EventRegistryMapper;
import com.fuyun.integration.service.EventRegistrationSpec;
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
        service = new EventRegistryServiceImpl();
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
