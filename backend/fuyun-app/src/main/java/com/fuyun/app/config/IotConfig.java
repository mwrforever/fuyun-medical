package com.fuyun.app.config;

import com.fuyun.iot.cache.IotSeqGate;
import com.fuyun.iot.config.IotAmqpConfig;
import com.fuyun.iot.config.IotMessagingConfig;
import com.fuyun.iot.config.IotRegistryConfig;
import com.fuyun.iot.config.IotWebSocketConfig;
import com.fuyun.iot.controller.AlarmController;
import com.fuyun.iot.controller.AlarmRuleController;
import com.fuyun.iot.controller.BindingController;
import com.fuyun.iot.controller.CommandController;
import com.fuyun.iot.controller.ConsumeErrorController;
import com.fuyun.iot.controller.DeviceController;
import com.fuyun.iot.controller.IotFallbackIngestController;
import com.fuyun.iot.controller.LinkageRuleController;
import com.fuyun.iot.controller.MetricDictController;
import com.fuyun.iot.controller.MonitorController;
import com.fuyun.iot.controller.ProductController;
import com.fuyun.iot.controller.QualityController;
import com.fuyun.iot.controller.TelemetryQueryController;
import com.fuyun.iot.internal.CommandDispatcher;
import com.fuyun.iot.internal.IotAlarmEventListener;
import com.fuyun.iot.internal.IotDeviceCommandListener;
import com.fuyun.iot.internal.IotFallbackAuthService;
import com.fuyun.iot.internal.LinkageExecutor;
import com.fuyun.iot.internal.alarm.AlarmEngine;
import com.fuyun.iot.internal.alarm.OfflineDetector;
import com.fuyun.iot.internal.alarm.StormGuard;
import com.fuyun.iot.properties.AlarmProperties;
import com.fuyun.iot.properties.CommandProperties;
import com.fuyun.iot.properties.IotProperties;
import com.fuyun.iot.properties.TelemetryValidationProperties;
import com.fuyun.iot.service.impl.AlarmRuleServiceImpl;
import com.fuyun.iot.service.impl.AlarmServiceImpl;
import com.fuyun.iot.service.impl.BindingServiceImpl;
import com.fuyun.iot.service.impl.CommandServiceImpl;
import com.fuyun.iot.service.impl.ConsumeErrorLogServiceImpl;
import com.fuyun.iot.service.impl.DeviceManageServiceImpl;
import com.fuyun.iot.service.impl.DeviceStatusServiceImpl;
import com.fuyun.iot.service.impl.LinkageRuleServiceImpl;
import com.fuyun.iot.service.impl.MetricDictServiceImpl;
import com.fuyun.iot.service.impl.ProductServiceImpl;
import com.fuyun.iot.service.impl.QualityServiceImpl;
import com.fuyun.iot.service.impl.TelemetryIngestServiceImpl;
import com.fuyun.iot.service.impl.TelemetryPushServiceImpl;
import com.fuyun.iot.service.impl.TelemetryQueryServiceImpl;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M14 医疗设备物联网模块装配：将 fuyun-iot 配置类引入 Boot 上下文的集中入口（backend 宪法 B.1
 * 装配归 app，与 MessagingConfig/SystemConfig 同模式，不放宽组件扫描）。
 *
 * <p>配置属性（fuyun.iot.*，含 AMQP 消费链与 HTTP 兜底通道参数，及 P2 PR-2 Task 6 遥测五步
 * 校验参数 fuyun.iot.telemetry.*——TelemetryIngestServiceImpl 消费）经
 * {@link EnableConfigurationProperties} 注册；遥测消费链三服务（入库/错误留痕/设备状态）与
 * B4.3 任务 B 四件（STOMP 推送服务/兜底鉴权/兜底端点/WS 端点配置）经 @Import 注册为 Bean
 * （com.fuyun.iot 不在扫描范围，宪法 B.1；mapper 由既有 @MapperScan 按注解自动覆盖）；
 * P2 PR-2 Task 3 追加绑定管理域三件（绑定服务/绑定五端点/AL-CMD 发号器——发号器构造注入
 * Boot Redis 自动配置 StringRedisTemplate，InpatientSeqGate 同款形态）；P2 PR-2 Task 4 追加
 * 产品与物模型管理域四件（产品服务/MDC 字典服务/产品六端点/字典两端点）与注册中心双实现
 * 装配（{@link IotRegistryConfig}——fuyun.iot.admin.enabled 默认 false 下装配 SimulatedRegistry，
 * CI/单测无云依赖；true 且凭证齐全装配 HuaweiIotdaRegistry，其 IotdaAdminProperties 随该
 * 配置类 @EnableConfigurationProperties 注册，单一注册路径防双注册冲突）；P2 PR-2 Task 5 追加
 * 设备管理域两件（设备管理服务——注册流水线/凭证热更新/CAS 停用，设备六端点）；
 * AMQP 消费链（Qpid 连接工厂/攒批器/SmartLifecycle 消费者/双指标绑定）经 {@link IotAmqpConfig}
 * 生效——该配置类带 enabled 开关条件装配，默认 {@code fuyun.iot.amqp.enabled=false} 下零连接
 * 尝试（存量 IT 回归零行为差异的保障）；MQ 事件总线域（fy.topic 状态事件发布器 + 自事件幂等
 * 消费者 + 治理队列声明）经 {@link IotMessagingConfig} 生效（无条件装配，与 AMQP 开关解耦）；
 * /ws/iot STOMP 端点与 HTTP 兜底端点无条件装配（B4.3 任务 B）；P2 PR-2 Task 7 追加告警域七件
 * （告警引擎/风暴抑制器/离线探测器、规则与告警双服务、规则与告警双端点）与告警引擎配置属性
 * （fuyun.iot.alarm.*）——引擎/服务依赖绑定快照、发号器与推送服务等既有装配链零新增外部依赖；
 * P2 PR-2 Task 8 追加命令域四件（命令下发编排器——五步下发实装单点/命令服务/命令四端点/命令
 * 结果帧监听器——AMQP 命令状态帧回推终态）与命令配置属性（fuyun.iot.command.*：同步等待超时
 * 与治疗级豁免开关）——编排器依赖白名单与发号器等既有装配链，TransactionTemplate 由 Boot 事务
 * 自动配置供给（终态 CAS 与事件发布同事务承载）；P2 PR-2 Task 9 追加联动域四件（联动执行器——
 * 触发→动作编排与失败重试单点/联动规则服务——规则 CRUD 与 FAILED 人工重推/规则与日志六端点/
 * 告警触发自事件监听器——联动触发源主入口，q.iot.iot.alarm.triggered 队列声明随
 * {@link IotMessagingConfig} 生效）——执行器依赖发号器/推送服务等既有装配链，TransactionTemplate
 * 由 Boot 事务自动配置供给（联动留痕落行与事件发布同事务承载）；P2 PR-2 Task 10 追加时序查询
 * 与质量监控域六件（遥测查询服务——三档查询路由 + ward 消费端口 IotTelemetryQueryPort 实现/
 * 质量监控服务——质量日统计惰性重算、遥测断流判定发布与消费积压快照采样/遥测两端点/质量两端点/
 * 积压监控端点/消费错误三端点——重放与放弃为 V401 P0 只写遗留的端点义务补齐，消费错误服务
 * 扩展处置面后既有装配行继续承载）。
 */
@Configuration
@EnableConfigurationProperties({
    IotProperties.class,
    TelemetryValidationProperties.class,
    AlarmProperties.class,
    CommandProperties.class
})
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
    DeviceManageServiceImpl.class,
    DeviceController.class,
    IotRegistryConfig.class,
    IotAmqpConfig.class,
    IotMessagingConfig.class,
    IotWebSocketConfig.class,
    // P2 PR-2 Task 7 告警域八件（FU-M14-08）：引擎/风暴抑制器/离线探测器、双服务、双端点
    // 与告警引擎配置属性（fuyun.iot.alarm.*）
    AlarmEngine.class,
    StormGuard.class,
    OfflineDetector.class,
    AlarmRuleServiceImpl.class,
    AlarmServiceImpl.class,
    AlarmRuleController.class,
    AlarmController.class,
    // P2 PR-2 Task 8 命令域四件（FU-M14-09）：下发编排器/命令服务/命令端点/命令结果帧监听器
    // 与命令配置属性（fuyun.iot.command.*——同步超时与治疗级豁免开关）
    CommandDispatcher.class,
    IotDeviceCommandListener.class,
    CommandServiceImpl.class,
    CommandController.class,
    // P2 PR-2 Task 9 联动域四件（FU-M14-10）：联动执行器（触发→动作编排）/规则服务/六端点/
    // 告警触发自事件监听器（联动触发源主入口；消费队列声明归 IotMessagingConfig）
    LinkageExecutor.class,
    LinkageRuleServiceImpl.class,
    LinkageRuleController.class,
    IotAlarmEventListener.class,
    // P2 PR-2 Task 10 时序查询与质量监控域六件（FU-M14-06/FU-M14-11）：遥测查询服务（三档路由，
    // 兼 ward 消费端口 IotTelemetryQueryPort 实现）/质量监控服务（统计惰性重算+断流判定+积压快照）/
    // 四端点（时序两端点/质量两端点/积压监控/消费错误三端点——消费错误重放放弃为 V401 P0 遗留
    // 端点义务补齐，随 ConsumeErrorLogServiceImpl 扩展处置面）
    TelemetryQueryServiceImpl.class,
    TelemetryQueryController.class,
    QualityServiceImpl.class,
    QualityController.class,
    MonitorController.class,
    ConsumeErrorController.class
})
public class IotConfig {}
