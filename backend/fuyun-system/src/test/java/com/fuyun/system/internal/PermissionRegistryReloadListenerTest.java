package com.fuyun.system.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.system.api.PermissionMatrixChangedPayload;
import com.fuyun.system.constants.SystemMessagingConstants;
import com.fuyun.system.service.ITokenService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * 权限矩阵变更消费者单元测试（PR-4F W-96② 双消费入口：治理命名队列幂等三步 + 匿名广播队列重载）。
 *
 * <p>覆盖：合规信封全链消费（重载 Registry + 受影响角色会话清理 + PROCESSED 登记，信封五要素
 * 完整）、重复投递幂等跳过（零动作零登记）、业务失败 settleFailure 失败收尾后原样重抛（交容器
 * 有界重试耗尽进 fy.dlx）、广播入口仅重载本实例 Registry 且重载失败吞异常留痕不外抛（降级旧
 * 矩阵继续生效）。真实 broker 广播与治理队列声明链路归集成测试把关。
 */
@ExtendWith(MockitoExtension.class)
class PermissionRegistryReloadListenerTest {

    /** 测试事件号：信封 eventId（UUID 形态，测试样本固定值保证幂等键断言确定性） */
    private static final String EVENT_ID = "d3e1b4c5-6f7a-4b82-9c93-1e4d5f6a7b81";

    /** 测试追踪锚点 */
    private static final String TRACE_ID = "it-perm-reload-trace";

    /** 信封发生时刻：五要素登记断言基准（UTC 语义） */
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-07T08:00:00Z");

    /** 变更角色码样本：会话清理入参断言锚点 */
    private static final String ROLE_CODE = "NURSE";

    @Mock
    private MessageIdempotencyService idempotencyService;

    @Mock
    private PermissionRegistry permissionRegistry;

    @Mock
    private ITokenService tokenService;

    @Captor
    private ArgumentCaptor<ReceivedEventRecord> recordCaptor;

    private PermissionRegistryReloadListener listener;

    @BeforeEach
    void setUp() {
        listener = new PermissionRegistryReloadListener(
                idempotencyService,
                new EventEnvelopeCodec(testObjectMapper()),
                testObjectMapper(),
                permissionRegistry,
                tokenService);
    }

    @Test
    @DisplayName("合规消费全链：重载 Registry + 按角色清理会话 + 按信封五要素登记 PROCESSED（consumerModule=system）")
    void consumesEnvelopeReloadsRegistryAndEvictsSessions() throws Exception {
        when(idempotencyService.tryAcquire(EVENT_ID, SystemMessagingConstants.MODULE))
                .thenReturn(true);

        listener.onPermissionMatrixChanged(message(toJson(compliantEnvelope())));

        // 业务动作双执行：本实例矩阵重载 + 受影响角色会话清理（单实例消费语义，F4）
        verify(permissionRegistry).load();
        verify(tokenService).evictSessionsByRoles(Set.of(ROLE_CODE));
        verify(idempotencyService).recordProcessed(recordCaptor.capture());
        ReceivedEventRecord record = recordCaptor.getValue();
        assertThat(record.eventId()).isEqualTo(EVENT_ID);
        assertThat(record.eventType()).isEqualTo(SystemMessagingConstants.EVENT_PERMISSION_CHANGED);
        assertThat(record.producer()).isEqualTo(SystemMessagingConstants.MODULE);
        assertThat(record.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(record.consumerModule()).isEqualTo(SystemMessagingConstants.MODULE);
    }

    @Test
    @DisplayName("重复投递幂等跳过：tryAcquire 返回 false 直接返回（AUTO 确认），零业务动作零登记")
    void skipsRedeliveredMessageWithoutBusinessOrRecord() throws Exception {
        when(idempotencyService.tryAcquire(EVENT_ID, SystemMessagingConstants.MODULE))
                .thenReturn(false);

        listener.onPermissionMatrixChanged(message(toJson(compliantEnvelope())));

        verify(permissionRegistry, never()).load();
        verify(tokenService, never()).evictSessionsByRoles(any());
        verify(idempotencyService, never()).recordProcessed(any());
        verify(idempotencyService, never()).settleFailure(any(), any());
    }

    @Test
    @DisplayName("业务失败收尾重抛：重载失败时 settleFailure 承接并原样上抛（交有界重试耗尽进 fy.dlx）")
    void settlesFailureAndRethrowsOnBusinessFailure() throws Exception {
        when(idempotencyService.tryAcquire(EVENT_ID, SystemMessagingConstants.MODULE))
                .thenReturn(true);
        IllegalStateException failure = new IllegalStateException("矩阵重载失败");
        doThrow(failure).when(permissionRegistry).load();

        assertThatThrownBy(() -> listener.onPermissionMatrixChanged(message(toJson(compliantEnvelope()))))
                .isSameAs(failure);
        verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), eq(failure));
        verify(idempotencyService, never()).recordProcessed(any());
        // 重载失败先于会话清理：清理通道不得触发（避免半套生效动作）
        verify(tokenService, never()).evictSessionsByRoles(any());
    }

    @Test
    @DisplayName("坏载荷失败收尾：载荷类型不符包成 IllegalStateException 上抛，settleFailure 留痕零业务动作（评审 B-I1）")
    void wrapsBadPayloadAsIllegalStateAndSettlesFailure() {
        when(idempotencyService.tryAcquire(EVENT_ID, SystemMessagingConstants.MODULE))
                .thenReturn(true);

        // roleCode 为嵌套对象：treeToValue 无法还原 String 契约字段（MismatchedInputException）
        assertThatThrownBy(() -> listener.onPermissionMatrixChanged(message(toJson(badPayloadEnvelope()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权限矩阵变更载荷与契约不符");
        // 失败收尾必须执行（释放前置键 + FAILED 留痕）——受检异常透出绕过此步即坏载荷无台账无死信
        verify(idempotencyService).settleFailure(recordCaptor.capture(), any(IllegalStateException.class));
        assertThat(recordCaptor.getValue().eventId()).isEqualTo(EVENT_ID);
        verify(idempotencyService, never()).recordProcessed(any());
        // 零业务动作：解析失败先于重载与会话清理（避免半套生效动作）
        verify(permissionRegistry, never()).load();
        verify(tokenService, never()).evictSessionsByRoles(any());
    }

    @Test
    @DisplayName("广播入口：仅重载本实例 Registry 不触会话清理与幂等；重载失败吞异常留痕不外抛（降级旧矩阵生效）")
    void broadcastReloadsRegistryOnlyAndSwallowsReloadFailure() {
        // 正常广播：每实例本地重载，会话清理归治理命名队列单实例消费，不经幂等服务
        listener.onPermissionMatrixBroadcast(message(toJson(compliantEnvelope())));

        verify(permissionRegistry).load();
        verify(tokenService, never()).evictSessionsByRoles(any());
        verify(idempotencyService, never()).tryAcquire(anyString(), anyString());

        // 重载失败：catch 后 error 留痕吞掉即 ack，异常不得透出容器（下一变更或重启自愈）
        doThrow(new IllegalStateException("广播重载失败")).when(permissionRegistry).load();
        assertThatCode(() -> listener.onPermissionMatrixBroadcast(message(toJson(compliantEnvelope()))))
                .doesNotThrowAnyException();
        verify(permissionRegistry, times(2)).load();
    }

    /** 构造合规信封样本：eventId 固定为样本常量，载荷 = PermissionMatrixChangedPayload 契约字段 */
    private EventEnvelope compliantEnvelope() {
        return new EventEnvelope(
                EVENT_ID,
                OCCURRED_AT,
                SystemMessagingConstants.MODULE,
                SystemMessagingConstants.EVENT_PERMISSION_CHANGED,
                "1",
                TRACE_ID,
                testObjectMapper().valueToTree(new PermissionMatrixChangedPayload(ROLE_CODE)));
    }

    /** 构造坏载荷信封样本：roleCode 为嵌套对象（String 契约字段无法还原），信封五要素本身合规 */
    private EventEnvelope badPayloadEnvelope() {
        return new EventEnvelope(
                EVENT_ID,
                OCCURRED_AT,
                SystemMessagingConstants.MODULE,
                SystemMessagingConstants.EVENT_PERMISSION_CHANGED,
                "1",
                TRACE_ID,
                testObjectMapper().valueToTree(Map.of("roleCode", Map.of("bad", true))));
    }

    /** 信封序列化为线格式 JSON（与生产发布侧同构，序列化失败属测试资产缺陷直接抛出） */
    private String toJson(EventEnvelope envelope) {
        try {
            return testObjectMapper().writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("测试信封序列化失败", e);
        }
    }

    /** 构造 raw 消息帧（UTF-8 编码 JSON body，与生产发布侧 contentType 同构） */
    private Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    /** 测试用 ObjectMapper：注册 JavaTimeModule 并关闭时间戳形态（对齐 Boot 全局定制实例行为） */
    private static ObjectMapper testObjectMapper() {
        return new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
