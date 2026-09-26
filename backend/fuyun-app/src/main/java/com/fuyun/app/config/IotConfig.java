package com.fuyun.app.config;

import com.fuyun.iot.cache.IotSeqGate;
import com.fuyun.iot.config.IotAmqpConfig;
import com.fuyun.iot.config.IotMessagingConfig;
import com.fuyun.iot.config.IotRegistryConfig;
import com.fuyun.iot.config.IotWebSocketConfig;
import com.fuyun.iot.controller.BindingController;
import com.fuyun.iot.controller.IotFallbackIngestController;
import com.fuyun.iot.controller.MetricDictController;
import com.fuyun.iot.controller.ProductController;
import com.fuyun.iot.internal.IotFallbackAuthService;
import com.fuyun.iot.properties.IotProperties;
import com.fuyun.iot.service.impl.BindingServiceImpl;
import com.fuyun.iot.service.impl.ConsumeErrorLogServiceImpl;
import com.fuyun.iot.service.impl.DeviceStatusServiceImpl;
import com.fuyun.iot.service.impl.MetricDictServiceImpl;
import com.fuyun.iot.service.impl.ProductServiceImpl;
import com.fuyun.iot.service.impl.TelemetryIngestServiceImpl;
import com.fuyun.iot.service.impl.TelemetryPushServiceImpl;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M14 医疗设备物联网模块装配：将 fuyun-iot 配置类引入 Boot 上下文的集中入口（backend 宪法 B.1
 * 装配归 app，与 MessagingConfig/SystemConfig 同模式，不放宽组件扫描）。
 *
 * <p>配置属性（fuyun.iot.*，含 AMQP 消费链与 HTTP 兜底通道参数）经
 * {@link EnableConfigurationProperties} 注册；遥测消费链三服务（入库/错误留痕/设备状态）与
 * B4.3 任务 B 四件（STOMP 推送服务/兜底鉴权/兜底端点/WS 端点配置）经 @Import 注册为 Bean
 * （com.fuyun.iot 不在扫描范围，宪法 B.1；mapper 由既有 @MapperScan 按注解自动覆盖）；
 * P2 PR-2 Task 3 追加绑定管理域三件（绑定服务/绑定五端点/AL-CMD 发号器——发号器构造注入
 * Boot Redis 自动配置 StringRedisTemplate，InpatientSeqGate 同款形态）；P2 PR-2 Task 4 追加
 * 产品与物模型管理域四件（产品服务/MDC 字典服务/产品六端点/字典两端点）与注册中心双实现
 * 装配（{@link IotRegistryConfig}——fuyun.iot.admin.enabled 默认 false 下装配 SimulatedRegistry，
 * CI/单测无云依赖；true 且凭证齐全装配 HuaweiIotdaRegistry，其 IotdaAdminProperties 随该
 * 配置类 @EnableConfigurationProperties 注册，单一注册路径防双注册冲突）；
 * AMQP 消费链（Qpid 连接工厂/攒批器/SmartLifecycle 消费者/双指标绑定）经 {@link IotAmqpConfig}
 * 生效——该配置类带 enabled 开关条件装配，默认 {@code fuyun.iot.amqp.enabled=false} 下零连接
 * 尝试（存量 IT 回归零行为差异的保障）；MQ 事件总线域（fy.topic 状态事件发布器 + 自事件幂等
 * 消费者 + 治理队列声明）经 {@link IotMessagingConfig} 生效（无条件装配，与 AMQP 开关解耦）；
 * /ws/iot STOMP 端点与 HTTP 兜底端点无条件装配（B4.3 任务 B）。
 */
@Configuration
@EnableConfigurationProperties(IotProperties.class)
@Import({
    TelemetryIngestServiceImpl.class,
    ConsumeErrorLogServiceImpl.class,
    DeviceStatusServiceImpl.class,
    TelemetryPushServiceImpl.class,
    IotFallbackAuthService.class,
    IotFallbackIngestController.class,
    BindingServiceImpl.class,
    BindingController.class,
    IotSeqGate.class,
    ProductServiceImpl.class,
    MetricDictServiceImpl.class,
    ProductController.class,
    MetricDictController.class,
    IotRegistryConfig.class,
    IotAmqpConfig.class,
    IotMessagingConfig.class,
    IotWebSocketConfig.class
})
public class IotConfig {}
