package com.fuyun.iot.internal.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.cache.IotSeqGate;
import com.fuyun.iot.entity.IotAlarmEntity;
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.ThresholdOp;
import com.fuyun.iot.internal.IotDomainPublisher;
import com.fuyun.iot.mapper.IotAlarmMapper;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.mapper.IotMetricDictMapper;
import com.fuyun.iot.mapper.IotMetricMappingMapper;
import com.fuyun.iot.mapper.IotTelemetryMapper;
import com.fuyun.iot.properties.AlarmProperties;
import com.fuyun.iot.service.IBindingService;
import com.fuyun.iot.service.ITelemetryPushService;
import com.fuyun.iot.service.impl.TelemetryIngestServiceImpl;
import com.fuyun.iot.vo.BindingVO;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 告警事件可达性组合路径测试（Task 7 审查 Critical-1 验证义务）：真实 Spring 事务基础设施
 * （DataSourceTransactionManager + {@code @EnableTransactionManagement} 真代理，DataSource/
 * Connection 为 mock 免 DB——链路内全部 SQL 经 mock mapper，事务语义不依赖数据库方言）驱动
 * 生产主路径完整链形——
 *
 * <pre>提交事务 { ingest 落行 } → afterCommit → AlarmEngine.evaluate[REQUIRES_NEW 新事务]
 * → 落行 + publishEvent(triggered) → 新事务提交 → AFTER_COMMIT 监听 → sender.send 真实送达
 * → WS 推送同步（引擎 pushAfterCommit 注册于新事务）→ pushAlarm 真实送达</pre>
 *
 * <p>锁定 Critical-1 修复：修复前 evaluate 以 REQUIRED 加入 afterCommit 阶段的死事务（afterCommit
 * 快照已发），triggered 事件与 WS 推送双双静默丢失，本测试在该形态下必失败（send/pushAlarm 均
 * 零调用）；修复后两送达点齐验。审查既定敞口申报：本测试为单测形态（mock Connection 无真实
 * DB 往返），生产库语义回归归 Task 18 IotAlarmClosedLoopIT 全链兜底。
 */
class AlarmEventReachabilityTest {

    private static final String DEVICE_ID = "dev-001";

    private static final String METRIC_CODE = "MDC_ECG_HEART_RATE";

    private static final long RULE_ID = 900001L;

    private static final String ALARM_NO = "AL2026092600001";

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次；覆盖 ingest 批查询三实体
        // 与告警域两实体——TelemetryIngestServiceImplTest/AlarmEngineTest 同款兜底）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, com.fuyun.iot.entity.IotDeviceEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.fuyun.iot.entity.IotMetricMappingEntity.class);
        TableInfoHelper.initTableInfo(assistant, com.fuyun.iot.entity.IotMetricDictEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotAlarmRuleEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotAlarmEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotTelemetryEntity.class);
    }

    @Test
    @DisplayName("Critical-1 注解锁：evaluate 强制 REQUIRES_NEW（afterCommit 调起点不可回退为 REQUIRED）")
    void evaluateDeclaresRequiresNewPropagation() throws Exception {
        Transactional transactional = AlarmEngine.class
                .getMethod("evaluate", AlarmEngine.TelemetryBatch.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional)
                .as("evaluate 必须显式声明 @Transactional（生产经代理调起，传播语义为可达性前提）")
                .isNotNull();
        assertThat(transactional.propagation())
                .as("afterCommit 阶段同步上下文仍激活，REQUIRED 会加入已提交死事务致事件/推送双丢失")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("组合路径：提交事务内 ingest 落行 → afterCommit 评估（REQUIRES_NEW）→ triggered 事件与 WS 推送真实送达")
    void triggeredEventReachesSenderThroughIngestAfterCommitChain() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(AlarmChainTestConfig.class)) {
            // 引擎必须经真实代理调起（REQUIRES_NEW 生效前提）
            AlarmEngine engine = context.getBean(AlarmEngine.class);
            assertThat(AopUtils.isAopProxy(engine))
                    .as("引擎 Bean 须被事务代理（传播语义生效载体）")
                    .isTrue();

            stubDependencies(context);
            TransactionTemplate outer = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            // 生产主路径形态：提交事务内完成 ingest 落行，afterCommit 触发评估
            // （ingest 实现接口，纯 Spring 默认 JDK 代理——按接口类型取 Bean，Boot 例外）
            outer.executeWithoutResult(status -> context.getBean(com.fuyun.iot.service.ITelemetryIngestService.class)
                    .ingest(List.of(message())));

            // ① MQ 事件真实送达：IotDomainPublisher AFTER_COMMIT 于 REQUIRES_NEW 新事务提交后触发
            ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
            verify(context.getBean(DomainEventSender.class))
                    .send(eq("iot.alarm.triggered"), payloadCaptor.capture(), any());
            assertThat(payloadCaptor.getValue()).isInstanceOf(com.fuyun.iot.api.payload.AlarmTriggeredPayload.class);
            assertThat(((com.fuyun.iot.api.payload.AlarmTriggeredPayload) payloadCaptor.getValue()).alarmNo())
                    .isEqualTo(ALARM_NO);
            // ② WS 推送真实送达：引擎 pushAfterCommit 注册于 REQUIRES_NEW 新事务、提交时触发
            verify(context.getBean(ITelemetryPushService.class)).pushAlarm(any(IotAlarmEntity.class));
        }
    }

    /** 链路依赖装桩：命中阈值规则 + 越限回合已起算达标 + 发号器/落行正常（风暴/抑制未命中） */
    private void stubDependencies(ApplicationContext context) {
        IBindingService bindingService = context.getBean(IBindingService.class);
        when(bindingService.listActiveByDevices(any()))
                .thenReturn(List.of(new BindingVO(
                        1L, DEVICE_ID, 5L, "20260901000001", null, 1001L, null, null, null, null, null, null, null)));
        when(context.getBean(IotTelemetryMapper.class).insertBatchIgnoreConflict(any()))
                .thenReturn(1);
        IotAlarmRuleEntity rule = new IotAlarmRuleEntity();
        rule.setId(RULE_ID);
        rule.setRuleName("心率过速危急告警");
        rule.setRuleType(AlarmRuleType.THRESHOLD);
        rule.setMetricCode(METRIC_CODE);
        rule.setCompareOp(ThresholdOp.GT);
        rule.setThresholdValue(new BigDecimal("150"));
        rule.setDurationSecs(30);
        rule.setRecoveryBand(new BigDecimal("10"));
        rule.setAlarmLevel(AlarmLevel.CRITICAL);
        rule.setEnabled(true);
        when(context.getBean(IotAlarmRuleMapper.class).selectList(any())).thenReturn(List.of(rule));
        when(context.getBean(IotSeqGate.class).nextAlarmNo()).thenReturn(ALARM_NO);
        when(context.getBean(IotAlarmMapper.class).insert(any(IotAlarmEntity.class)))
                .thenReturn(1);
        // 越限回合标记已起算 60s（≥ 持续 30s）：本批评估即触发
        StringRedisTemplate redisTemplate = context.getBean(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("fy:iot:alarm:breach:" + RULE_ID + ":" + DEVICE_ID + ":" + METRIC_CODE))
                .thenReturn(Instant.now().minusSeconds(60).toString());
        when(valueOperations.increment("fy:iot:alarm:rate:" + RULE_ID)).thenReturn(1L);
    }

    /** 遥测消息夹具（CF-7 心率 170 越限帧） */
    private static StandardTelemetryMessage message() {
        return new StandardTelemetryMessage(DEVICE_ID, METRIC_CODE, "170", null, Instant.now(), "GOOD", "IOTDA");
    }

    /**
     * 链路装配（真实事务基础设施 + 真实引擎/入库/发布器组件；IO 边界全 mock）：
     * {@code @EnableTransactionManagement} 令 ingest（REQUIRED）与引擎（REQUIRES_NEW）经真实代理
     * 承载生产传播语义；DataSourceTransactionManager 以 mock DataSource/Connection 承载真实
     * 挂起/提交/afterCommit 链（无真实 DB——SQL 全走 mock mapper，无需方言）。
     */
    @Configuration
    @EnableTransactionManagement
    static class AlarmChainTestConfig {

        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        Connection connection(DataSource dataSource) throws Exception {
            Connection connection = mock(Connection.class);
            when(dataSource.getConnection()).thenReturn(connection);
            return connection;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        AlarmProperties alarmProperties() {
            return new AlarmProperties(Duration.ofMinutes(10), 50, Duration.ofMinutes(10));
        }

        @Bean
        IotAlarmRuleMapper alarmRuleMapper() {
            return mock(IotAlarmRuleMapper.class);
        }

        @Bean
        IotAlarmMapper alarmMapper() {
            return mock(IotAlarmMapper.class);
        }

        @Bean
        IotDeviceMapper deviceMapper() {
            return mock(IotDeviceMapper.class);
        }

        @Bean
        IotTelemetryMapper telemetryMapper() {
            return mock(IotTelemetryMapper.class);
        }

        @Bean
        IotMetricMappingMapper metricMappingMapper() {
            return mock(IotMetricMappingMapper.class);
        }

        @Bean
        IotMetricDictMapper metricDictMapper() {
            return mock(IotMetricDictMapper.class);
        }

        @Bean
        IBindingService bindingService() {
            return mock(IBindingService.class);
        }

        @Bean
        IotSeqGate seqGate() {
            return mock(IotSeqGate.class);
        }

        @Bean
        ITelemetryPushService pushService() {
            return mock(ITelemetryPushService.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        DomainEventSender domainEventSender() {
            return mock(DomainEventSender.class);
        }

        @Bean
        IotDomainPublisher iotDomainPublisher(DomainEventSender sender) {
            return new IotDomainPublisher(sender);
        }

        @Bean
        StormGuard stormGuard(
                IotAlarmMapper alarmMapper,
                IotAlarmRuleMapper ruleMapper,
                StringRedisTemplate redisTemplate,
                AlarmProperties properties) {
            return new StormGuard(alarmMapper, ruleMapper, redisTemplate, properties);
        }

        @Bean
        OfflineDetector offlineDetector(IotAlarmRuleMapper ruleMapper, IotDeviceMapper deviceMapper) {
            return new OfflineDetector(ruleMapper, deviceMapper);
        }

        @Bean
        AlarmEngine alarmEngine(
                IotAlarmRuleMapper ruleMapper,
                IotAlarmMapper alarmMapper,
                IotDeviceMapper deviceMapper,
                IBindingService bindingService,
                IotSeqGate seqGate,
                ApplicationContext events,
                ITelemetryPushService pushService,
                StormGuard stormGuard,
                OfflineDetector offlineDetector,
                StringRedisTemplate redisTemplate,
                AlarmProperties properties) {
            return new AlarmEngine(
                    ruleMapper,
                    alarmMapper,
                    deviceMapper,
                    bindingService,
                    seqGate,
                    events,
                    pushService,
                    stormGuard,
                    offlineDetector,
                    redisTemplate,
                    properties);
        }

        @Bean
        TelemetryIngestServiceImpl telemetryIngestService(
                IBindingService bindingService,
                IotTelemetryMapper telemetryMapper,
                ITelemetryPushService pushService,
                IotDeviceMapper deviceMapper,
                IotMetricMappingMapper metricMappingMapper,
                IotMetricDictMapper metricDictMapper,
                StringRedisTemplate redisTemplate,
                AlarmProperties alarmProperties,
                AlarmEngine alarmEngine) {
            // 校验属性取真实 record 实例（默认阈值 300s、空波形白名单——体征行不受影响）
            return new TelemetryIngestServiceImpl(
                    bindingService,
                    telemetryMapper,
                    pushService,
                    deviceMapper,
                    metricMappingMapper,
                    metricDictMapper,
                    redisTemplate,
                    new com.fuyun.iot.properties.TelemetryValidationProperties(
                            Duration.ofSeconds(300), List.of(), 1_000_000),
                    alarmEngine);
        }
    }
}
