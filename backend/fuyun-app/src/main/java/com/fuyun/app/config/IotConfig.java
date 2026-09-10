package com.fuyun.app.config;

import com.fuyun.iot.config.IotAmqpConfig;
import com.fuyun.iot.config.IotMessagingConfig;
import com.fuyun.iot.config.IotWebSocketConfig;
import com.fuyun.iot.controller.IotFallbackIngestController;
import com.fuyun.iot.internal.IotFallbackAuthService;
import com.fuyun.iot.properties.IotProperties;
import com.fuyun.iot.service.impl.ConsumeErrorLogServiceImpl;
import com.fuyun.iot.service.impl.DeviceStatusServiceImpl;
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
    IotAmqpConfig.class,
    IotMessagingConfig.class,
    IotWebSocketConfig.class
})
public class IotConfig {}
