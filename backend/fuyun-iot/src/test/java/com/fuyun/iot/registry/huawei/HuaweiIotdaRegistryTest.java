package com.fuyun.iot.registry.huawei;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.properties.IotdaAdminProperties;
import com.fuyun.iot.registry.ProductRef;
import com.fuyun.iot.registry.RegistryDeviceSpec;
import com.fuyun.iot.registry.RegistryException;
import com.huaweicloud.sdk.core.HcClient;
import com.huaweicloud.sdk.core.exception.ClientRequestException;
import com.huaweicloud.sdk.core.exception.ConnectionException;
import com.huaweicloud.sdk.core.exception.SdkErrorMessage;
import com.huaweicloud.sdk.core.http.HttpConfig;
import com.huaweicloud.sdk.core.invoker.SyncInvoker;
import com.huaweicloud.sdk.iotda.v5.IoTDAClient;
import com.huaweicloud.sdk.iotda.v5.model.AddDeviceResponse;
import com.huaweicloud.sdk.iotda.v5.model.AddProduct;
import com.huaweicloud.sdk.iotda.v5.model.CreateProductRequest;
import com.huaweicloud.sdk.iotda.v5.model.CreateProductResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 华为云 IoTDA Registry 单测（P2 PR-2 Task 4 Step 2，mock SDK client）：成功映射
 * （createProduct 请求体携带产品名 + 响应 productId 回填）、SDK 异常统一转 RegistryException
 * （IOT-1022 语义）、一机一密 secret 不落日志断言（ListAppender 捕获，凭证红线）。
 *
 * <p>EX-40 外部客户端防御配置（B.4-2，2026-09-28 性能收拢组）：SDK 调用面打桩点从直接方法
 * （createProduct/addDevice）改 invoker 工厂（createProductInvoker/addDeviceInvoker，重试经
 * invoker 链承载——SDK 无 client 级重试配置项）；新增超时配置生效面（客户端构造参数断言）、
 * 有界重试挂载、熔断开断/半开探测/4xx 不计入五用例。
 *
 * <p>IoTDAClient 与 SyncInvoker 以 Mockito 模拟（真实云端行为归联调环境验证）；client 惰性
 * 构建字段经反射注入 mock（单测构造范式与 BindingServiceImplTest 同源）。
 */
class HuaweiIotdaRegistryTest {

    private static final String PRODUCT_ID = "prod-iotda-001";

    private static final String DEVICE_ID = "dev-iotda-001";

    private IoTDAClient mockClient;

    private HuaweiIotdaRegistry registry;

    /** 日志捕获器：secret 不落日志断言载体（挂实现类 logger） */
    private ListAppender<ILoggingEvent> logAppender;

    private Logger registryLogger;

    @BeforeEach
    void setUp() {
        registry = new HuaweiIotdaRegistry(adminProperties(), new ObjectMapper());
        mockClient = mock(IoTDAClient.class);
        // 惰性构建字段直注 mock（绕过真实 client 构建路径——凭证/端点不触网）
        ReflectionTestUtils.setField(registry, "client", mockClient);
    }

    @AfterEach
    void tearDown() {
        if (registryLogger != null && logAppender != null) {
            registryLogger.detachAppender(logAppender);
        }
    }

    @Test
    @DisplayName("createProduct 成功：请求体携带产品名，响应 productId 回填 ProductRef")
    void createProductMapsRequestAndResponse() throws Exception {
        // invoker 桩先建再入 when（桩内再起 when 会撞外层未完成桩——UnfinishedStubbing）
        SyncInvoker<CreateProductRequest, CreateProductResponse> invoker =
                stubInvokerReturning(new CreateProductResponse().withProductId(PRODUCT_ID));
        when(mockClient.createProductInvoker(any(CreateProductRequest.class))).thenReturn(invoker);

        ProductRef ref = registry.createProduct(
                new com.fuyun.iot.registry.ProductSpec("多参数监护仪", "MONITOR", "MQTT", "JSON", "厂商", "医疗设备", "描述", null));

        assertThat(ref.productId()).as("注册中心分配的产品标识回填").isEqualTo(PRODUCT_ID);
        ArgumentCaptor<CreateProductRequest> captor = ArgumentCaptor.forClass(CreateProductRequest.class);
        verify(mockClient).createProductInvoker(captor.capture());
        AddProduct body = captor.getValue().getBody();
        assertThat(body).as("createProduct 请求体为 AddProduct 形态").isNotNull();
        assertThat(body.getName()).as("产品名透传注册中心").isEqualTo("多参数监护仪");
    }

    @Test
    @DisplayName("SDK 异常统一转 RegistryException（IOT-1022 注册中心不可用语义）")
    void sdkExceptionTranslatesToRegistryException() throws Exception {
        when(mockClient.createProductInvoker(any(CreateProductRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> registry.createProduct(new com.fuyun.iot.registry.ProductSpec(
                        "多参数监护仪", "MONITOR", "MQTT", "JSON", null, null, null, null)))
                .as("SDK 层任意异常经统一兜底转为 RegistryException")
                .isInstanceOf(RegistryException.class)
                .hasMessageContaining("IoTDA");
    }

    @Test
    @DisplayName("registerDevice 成功：回传 secret 与写入云端凭证一致，且日志零 secret 明文（凭证红线）")
    void registerDeviceLogsContainNoSecret() throws Exception {
        registryLogger = (Logger) LoggerFactory.getLogger(HuaweiIotdaRegistry.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        registryLogger.addAppender(logAppender);

        // invoker 桩先建再入 when（桩内再起 when 会撞外层未完成桩——UnfinishedStubbing）
        SyncInvoker<com.huaweicloud.sdk.iotda.v5.model.AddDeviceRequest, AddDeviceResponse> invoker =
                stubInvokerReturning(new AddDeviceResponse().withDeviceId(DEVICE_ID));
        when(mockClient.addDeviceInvoker(any(com.huaweicloud.sdk.iotda.v5.model.AddDeviceRequest.class)))
                .thenReturn(invoker);

        var credential = registry.registerDevice(new RegistryDeviceSpec(DEVICE_ID, "node-001", PRODUCT_ID, "监护仪"));

        // 回传 secret 与实际写入云端的凭证一致（一机一密语义：云端存储即返回值）
        ArgumentCaptor<com.huaweicloud.sdk.iotda.v5.model.AddDeviceRequest> captor =
                ArgumentCaptor.forClass(com.huaweicloud.sdk.iotda.v5.model.AddDeviceRequest.class);
        verify(mockClient).addDeviceInvoker(captor.capture());
        assertThat(captor.getValue().getBody().getAuthInfo().getSecret())
                .as("返回的 secret 即写入云端的凭证明文")
                .isEqualTo(credential.secret());
        assertThat(logAppender.list)
                .as("全部日志事件（含异常堆栈消息）不含 secret 明文")
                .extracting(ILoggingEvent::getFormattedMessage)
                .satisfies(
                        events -> events.forEach(message -> assertThat(message).doesNotContain(credential.secret())));
        assertThat(logAppender.list).as("注册动作本身留 info 踪迹（含设备标识，不含凭证）").anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains(DEVICE_ID);
        });
    }

    // ===== EX-40：客户端超时/重试/熔断配置生效面（B.4-2 外部客户端条款）=====

    @Test
    @DisplayName("客户端构造参数断言：SDK client 显式携带连接 10s/读 30s 超时（B.4-2 显式超时）")
    void clientBuildCarriesExplicitTimeoutConfig() {
        IoTDAClient built = registry.buildClient();

        // 构造参数生效面：经反射读 SDK client 内部 HcClient 持有的 HttpConfig（SDK 无公开 getter 面）
        HcClient hcClient = (HcClient) ReflectionTestUtils.getField(built, "hcClient");
        assertThat(hcClient).as("SDK client 构建成功且携带内部 HcClient").isNotNull();
        HttpConfig config = hcClient.getHttpConfig();
        assertThat(config.getConnectionTimeout())
                .as("连接超时显式锁定（SDK 缺省 60s 与管理台同步链路失配）")
                .isEqualTo(HuaweiIotdaRegistry.CONNECT_TIMEOUT_SECONDS);
        assertThat(config.getReadTimeout())
                .as("读超时显式锁定（SDK 缺省 120s 与管理台同步链路失配）")
                .isEqualTo(HuaweiIotdaRegistry.READ_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("SDK 调用面携带有界重试：每次调用恰 1 次挂载 withRetry(2, SDK 缺省连接级条件)（B.4-2 有界重试）")
    void sdkCallsCarryBoundedRetry() {
        SyncInvoker<CreateProductRequest, CreateProductResponse> invoker =
                stubInvokerReturning(new CreateProductResponse().withProductId(PRODUCT_ID));
        when(mockClient.createProductInvoker(any(CreateProductRequest.class))).thenReturn(invoker);

        ProductRef ref = registry.createProduct(
                new com.fuyun.iot.registry.ProductSpec("多参数监护仪", "MONITOR", "MQTT", "JSON", null, null, null, null));

        assertThat(ref.productId()).isEqualTo(PRODUCT_ID);
        // 重试生效面：SDK 原生 invoker 链显式挂载有界重试（恰 1 次；重试上限取 2——SDK 硬顶 30 内，
        // 仅连接级异常触发，非幂等写安全）；invoke 恰 1 次即真实云端调用恰 1 次
        verify(invoker).withRetry(eq(HuaweiIotdaRegistry.RETRY_TIMES), any());
        verify(invoker).invoke();
    }

    @Test
    @DisplayName("熔断开断：连续 5 次云端失败后开断，后续调用快速失败且不再触云端（invoke 恰 5 次）")
    void circuitOpensAfterConsecutiveFailures() {
        SyncInvoker<CreateProductRequest, CreateProductResponse> invoker =
                stubInvokerThrowing(new ConnectionException("connection refused"));
        when(mockClient.createProductInvoker(any(CreateProductRequest.class))).thenReturn(invoker);

        // 连续失败达阈值（每次均触云端：ConnectionException 属云端不可用信号，计入熔断）
        for (int i = 0; i < HuaweiIotdaRegistry.CIRCUIT_FAILURE_THRESHOLD; i++) {
            assertThatThrownBy(this::createProduct)
                    .isInstanceOf(RegistryException.class)
                    .hasMessageContaining("IoTDA 产品创建失败");
        }
        // 开断后快速失败：熔断拦截（消息含熔断字样）且不再触云端（invoke 保持 5 次）
        assertThatThrownBy(this::createProduct)
                .isInstanceOf(RegistryException.class)
                .hasMessageContaining("熔断");
        verify(invoker, times(HuaweiIotdaRegistry.CIRCUIT_FAILURE_THRESHOLD)).invoke();
    }

    @Test
    @DisplayName("熔断半开探测：冷却到期后放行探测调用，成功即闭合并清零计数（后续调用直达云端）")
    void circuitHalfOpenProbeRestoresAfterCooldown() {
        SyncInvoker<CreateProductRequest, CreateProductResponse> invoker =
                stubInvokerThrowing(new ConnectionException("connection refused"));
        when(mockClient.createProductInvoker(any(CreateProductRequest.class))).thenReturn(invoker);

        for (int i = 0; i < HuaweiIotdaRegistry.CIRCUIT_FAILURE_THRESHOLD; i++) {
            assertThatThrownBy(this::createProduct).isInstanceOf(RegistryException.class);
        }
        assertThatThrownBy(this::createProduct).hasMessageContaining("熔断");
        int invokesWhenOpen = HuaweiIotdaRegistry.CIRCUIT_FAILURE_THRESHOLD;

        // 冷却到期模拟：回拨开断截止时刻到过去（30s 真实冷却不入单测等待）
        ReflectionTestUtils.setField(registry, "circuitOpenUntilMillis", System.currentTimeMillis() - 1L);

        // 半开探测放行：本次调用直达云端，成功即闭合（计数清零、开断时刻归零）。改桩用
        // doReturn 形态——when(invoker.invoke()) 会先触发旧 thenThrow 桩致改桩中断
        org.mockito.Mockito.doReturn(new CreateProductResponse().withProductId(PRODUCT_ID))
                .when(invoker)
                .invoke();
        assertThat(createProduct().productId()).isEqualTo(PRODUCT_ID);
        assertThat(ReflectionTestUtils.getField(registry, "circuitOpenUntilMillis"))
                .as("半开探测成功即整体闭合（开断时刻清零）")
                .isEqualTo(0L);
        java.util.concurrent.atomic.AtomicInteger failures = (java.util.concurrent.atomic.AtomicInteger)
                ReflectionTestUtils.getField(registry, "consecutiveFailures");
        assertThat(failures.get()).as("闭合时连续失败计数清零").isZero();

        // 闭合后再失败 1 次不触熔断：调用直达云端（计数已清零，单次失败远未达阈值）；
        // 同上用 doThrow 形态改桩（when 形态会触发旧桩）
        org.mockito.Mockito.doThrow(new ConnectionException("connection refused"))
                .when(invoker)
                .invoke();
        assertThatThrownBy(this::createProduct)
                .isInstanceOf(RegistryException.class)
                .hasMessageContaining("IoTDA 产品创建失败")
                .hasMessageNotContaining("熔断");
        verify(invoker, times(invokesWhenOpen + 2)).invoke();
    }

    @Test
    @DisplayName("熔断失败口径：4xx 请求侧异常不计入连续失败（云端健康可达不开断，业务拒绝不误伤）")
    void circuitDoesNotCountClientRequestExceptions() {
        SyncInvoker<CreateProductRequest, CreateProductResponse> invoker = stubInvokerThrowing(
                new ClientRequestException(404, new SdkErrorMessage("IOTDA.00011001", "product not found")));
        when(mockClient.createProductInvoker(any(CreateProductRequest.class))).thenReturn(invoker);

        // 4xx 连击超过阈值：均计入失败为零，调用持续直达云端（无熔断拦截）
        for (int i = 0; i <= HuaweiIotdaRegistry.CIRCUIT_FAILURE_THRESHOLD; i++) {
            assertThatThrownBy(this::createProduct)
                    .isInstanceOf(RegistryException.class)
                    .hasMessageContaining("IoTDA 产品创建失败")
                    .hasMessageNotContaining("熔断");
        }
        verify(invoker, times(HuaweiIotdaRegistry.CIRCUIT_FAILURE_THRESHOLD + 1))
                .invoke();
    }

    /** createProduct 便捷调用面（熔断用例复用：产品规格夹具统一入参） */
    private ProductRef createProduct() {
        return registry.createProduct(
                new com.fuyun.iot.registry.ProductSpec("多参数监护仪", "MONITOR", "MQTT", "JSON", null, null, null, null));
    }

    /**
     * SDK invoker 桩（成功形态）：withRetry 链自返回（EX-40 后 SDK 调用统一经 invoker 挂重试），
     * invoke 回放指定响应。
     *
     * @param response invoke 回放的 SDK 响应，非空
     * @return invoker 桩，非空
     */
    @SuppressWarnings("unchecked")
    private <R, S> SyncInvoker<R, S> stubInvokerReturning(S response) {
        SyncInvoker<R, S> invoker = mock(SyncInvoker.class);
        when(invoker.withRetry(anyInt(), any())).thenReturn(invoker);
        when(invoker.invoke()).thenReturn(response);
        return invoker;
    }

    /**
     * SDK invoker 桩（失败形态）：withRetry 链自返回，invoke 恒抛指定异常（熔断计数驱动面）。
     *
     * @param failure invoke 恒抛的运行时异常，非空
     * @return invoker 桩，非空
     */
    @SuppressWarnings("unchecked")
    private <R, S> SyncInvoker<R, S> stubInvokerThrowing(RuntimeException failure) {
        SyncInvoker<R, S> invoker = mock(SyncInvoker.class);
        when(invoker.withRetry(anyInt(), any())).thenReturn(invoker);
        when(invoker.invoke()).thenThrow(failure);
        return invoker;
    }

    /** 启用态管理面属性夹具（测试资产假凭证，与任何真实 IOTDA 凭证无关） */
    private static IotdaAdminProperties adminProperties() {
        return new IotdaAdminProperties(true, "https://iotda-endpoint.example.com", "project-1", "test-ak", "test-sk");
    }
}
