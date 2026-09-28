package com.fuyun.iot.registry.huawei;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
 * 华为云 IoTDA Registry 单测（P2 PR-2 Task 4 Step 2，mock SDK client 三用例）：成功映射
 * （createProduct 请求体携带产品名 + 响应 productId 回填）、SDK 异常统一转 RegistryException
 * （IOT-1022 语义）、一机一密 secret 不落日志断言（ListAppender 捕获，凭证红线）。
 *
 * <p>IoTDAClient 以 Mockito 模拟（真实云端行为归联调环境验证）；client 惰性构建字段经反射注入
 * mock（单测构造范式与 BindingServiceImplTest 同源）。
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
        CreateProductResponse response = new CreateProductResponse().withProductId(PRODUCT_ID);
        when(mockClient.createProduct(any(CreateProductRequest.class))).thenReturn(response);

        ProductRef ref = registry.createProduct(
                new com.fuyun.iot.registry.ProductSpec("多参数监护仪", "MONITOR", "MQTT", "JSON", "厂商", "医疗设备", "描述", null));

        assertThat(ref.productId()).as("注册中心分配的产品标识回填").isEqualTo(PRODUCT_ID);
        ArgumentCaptor<CreateProductRequest> captor = ArgumentCaptor.forClass(CreateProductRequest.class);
        verify(mockClient).createProduct(captor.capture());
        AddProduct body = captor.getValue().getBody();
        assertThat(body).as("createProduct 请求体为 AddProduct 形态").isNotNull();
        assertThat(body.getName()).as("产品名透传注册中心").isEqualTo("多参数监护仪");
    }

    @Test
    @DisplayName("SDK 异常统一转 RegistryException（IOT-1022 注册中心不可用语义）")
    void sdkExceptionTranslatesToRegistryException() throws Exception {
        when(mockClient.createProduct(any(CreateProductRequest.class)))
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

        AddDeviceResponse response = new AddDeviceResponse().withDeviceId(DEVICE_ID);
        when(mockClient.addDevice(any(com.huaweicloud.sdk.iotda.v5.model.AddDeviceRequest.class)))
                .thenReturn(response);

        var credential = registry.registerDevice(new RegistryDeviceSpec(DEVICE_ID, "node-001", PRODUCT_ID, "监护仪"));

        // 回传 secret 与实际写入云端的凭证一致（一机一密语义：云端存储即返回值）
        ArgumentCaptor<com.huaweicloud.sdk.iotda.v5.model.AddDeviceRequest> captor =
                ArgumentCaptor.forClass(com.huaweicloud.sdk.iotda.v5.model.AddDeviceRequest.class);
        verify(mockClient).addDevice(captor.capture());
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

    /** 启用态管理面属性夹具（测试资产假凭证，与任何真实 IOTDA 凭证无关） */
    private static IotdaAdminProperties adminProperties() {
        return new IotdaAdminProperties(true, "https://iotda-endpoint.example.com", "project-1", "test-ak", "test-sk");
    }
}
