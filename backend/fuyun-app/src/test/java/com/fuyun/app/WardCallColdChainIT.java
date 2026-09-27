package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.BindType;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.enums.DeviceAccessMode;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.enums.ThresholdOp;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.entity.WardCallRoutingRuleEntity;
import com.fuyun.ward.enums.CallType;
import com.fuyun.ward.mapper.WardCallRoutingRuleMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * M16 智慧病房呼叫/冷链验收锚点 IT（P2 PR-2 Task 18 第⑦条）：四断言面全部真栈——
 * ①输液告急告警事件经 q.ward.iot.alarm.triggered 消费链自动落 ward_call INFUSION 行
 * （红档落行 + 非红档不落行双向锚定）；②呼叫状态机全合法迁移 + 终态/非法迁移拒 + 同床位
 * 合并取消 + 路由规则解析（命中/无命中回滚）+ 设备源手工入口 DTO 拒；③冷链建档 + ALARM_HANDLE
 * 双人校验 + ward.cold-chain.alert-archived 事件真实送达捕获；④巡检 overdue 判定三态
 * （当日 0 次/1 次逾期、2 次新鲜不逾期）。
 *
 * <p><b>M16/M14 跨模块组合路径兜底</b>：ward 单测无真实 broker/DB，「ingest afterCommit →
 * AlarmEngine REQUIRES_NEW 落告警行 → iot.alarm.triggered 出 fy.topic → ward 常驻监听器
 * （WardConfig @Import 自动起）消费落呼叫行」的组合链路由步骤②实链验证——兜底通道
 * /ingest/iotda-fallback 对 token 202 同步完成 ingest 与评估，ward 消费为异步，DB 轮询等待；
 * 冷链归档事件同理由治理声明的 q.it.ward.cold-chain.alert-archived 捕获队列真实收帧锚定
 * （AFTER_COMMIT 出 MQ 组合路径，Task 12 单测敞口的本 IT 兜底断言）。
 *
 * <p>六步断言按 @Order 串联（告警号/档案号跨步复用属业务链路语义）：①种子（输液设备×2 +
 * 设备1 BOUND 绑定 + LT 阈值规则 + 呼叫路由规则 + admin 操作者锚值）；②输液告急全链（红档
 * "4"→"3" 两批越限触发落呼叫行；非红档设备 "5.5"→"5.2" 告警在位而呼叫行零落）；③呼叫状态机
 * 全迁移+合并取消+路由（六态出边逐一迁移、终态/CAS 违例 409 WD-1002、时段命中 route 200、
 * 无命中 409 WD-1003 事务回滚、IOT 源手工 400）；④升级读时惰性（created_at 回填 310s 前 →
 * GET 详情 escalation_count=1 且二次读不重发）；⑤冷链建档 + 双人校验（缺件 400 WD-1005、
 * 档案缺 404 WD-1004、登记成功 recorded_by/second_operator 两列分属两人 + 归档帧六组件锚定）；
 * ⑥overdue 三态与 INSPECTION/DEVIATION 零事件帧计数复核。
 *
 * <p>容器三件套与 {@link IotAlarmClosedLoopIT} 完全同款（tag 与 deploy compose 严格一致 +
 * it/rabbitmq.conf 挂载 + 类级独占 + @ServiceConnection）；登录/POST 助手复用
 * {@link FuyunStackITBase}（admin/Fuyun@2026）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WardCallColdChainIT extends FuyunStackITBase {

    /** TimescaleDB 容器：ward_call/cold_chain_* 与 iot_alarm 断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：ward 号段发号器（fy:ward:seq:*）/越限回合标记/消费幂等载体 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：fy.topic 事件扇出与治理捕获队列载体（本类独占 broker） */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假兜底共享密钥（fuyun.iot.fallback.token，仅具 IT 意义，与任何真实凭证无关） */
    private static final String TEST_FALLBACK_TOKEN = "it-ward-call-chain-fallback-token";

    /** 红档输液设备号（BOUND 绑定承载病区/患者路由，步骤②落呼叫行断言锚） */
    private static final String INFUSION_DEVICE_ID = "it-ward-inf-001";

    /** 非红档输液设备号（仅设备档案病区兜底，验证「>5ml 不落行」负路径） */
    private static final String YELLOW_DEVICE_ID = "it-ward-inf-002";

    /** 冷链监测设备号（建档归属锚，无 iot 链路依赖——档案仅存设备号文本） */
    private static final String COLD_CHAIN_DEVICE_ID = "it-ward-cc-001";

    /** 输液告急指标编码：词表缺位申报值（ward 监听器以 metricCode 精确相等判定，V1008 种子规则编码不相干无串扰） */
    private static final String METRIC_CODE = WardMessagingConstants.INFUSION_SHORTAGE_METRIC_CODE;

    /** 病区路由断言值（设备档案与绑定快照同 ward，ward 监听器落行 ward_id 同源） */
    private static final long WARD_ID = 1001L;

    /** 绑定快照断言值：患者 ID（雪花段外固定值） */
    private static final long PATIENT_ID = 91001L;

    /** 绑定快照断言值：就诊号（CF-3 I 型 14 位） */
    private static final String VISIT_ID = "I2026090100001";

    /** 合并取消床位 A（先建后建同号段，旧行被合并的断言载体） */
    private static final long BED_MERGE = 3001L;

    /** 转接侧支链路专用床位（与合并床位隔离防串扰） */
    private static final long BED_TRANSFER = 3002L;

    /** 路由规则命中链专用床位（NORMAL 型，命中种子时段规则） */
    private static final long BED_ROUTE_HIT = 3003L;

    /** 路由规则无命中链专用床位（SERVICE 型，WD-1003 回滚断言载体） */
    private static final long BED_ROUTE_MISS = 3004L;

    /** 读时惰性升级断言专用床位 */
    private static final long BED_ESCALATE = 3005L;

    /** 阈值规则持续时长秒：1 秒（两批间 Thread.sleep 1.2s 即达标，IT 耗时可控） */
    private static final int DURATION_SECS = 1;

    /** 两批越限帧间的等待毫秒：1.2s（覆盖 1s 持续时长 + 调度余量） */
    private static final long EPISODE_WAIT_MILLIS = 1_200L;

    /** 异步消费链路轮询等待上限：捕获队列收帧与 ward 消费链落行的确定性等待 */
    private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(30);

    /** DB/MQ 轮询步进 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /**
     * 非红档不落行/零事件类断言的复核窗口毫秒：ward 消费与归档事件均为同 JVM 本地监听，
     * 前序红档帧已被消费处理证明链路活跃，复核窗口内未出现即判定不落行/未发布
     */
    private static final long ABSENCE_RECHECK_MILLIS = 1_500L;

    /** 升级时限回填秒数：310s（越过 300s 常量阈值留 10s 余量，IT 内确定性触发读时惰性升级） */
    private static final String BACKDATE_SECONDS = "310 seconds";

    /** 路由规则时段格式：HHmm（V1100 time_range 词表，与 WardCallServiceImpl.inTimeRange 解析同源） */
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");

    /**
     * 捕获队列声明（A.5-4 治理红线：禁测试自声明交换机/裸队列——经 MessagingGovernance 声明，
     * IotAlarmClosedLoopIT 的 "it" 消费者模块形态）。两事件两列表：iot.alarm.triggered（步骤②
     * 红档/非红档两帧，ward 落行勾稽锚）与 ward.cold-chain.alert-archived（步骤⑤唯一帧 +
     * 步骤⑥零事件帧计数锚），声明副作用把 "it" 追加进订阅清单。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_ALARM_TRIGGERED =
                MessagingConstants.QUEUE_PREFIX + "it." + IotMessagingConstants.EVENT_ALARM_TRIGGERED;

        static final String Q_ALERT_ARCHIVED =
                MessagingConstants.QUEUE_PREFIX + "it." + WardMessagingConstants.EVENT_COLD_CHAIN_ALERT_ARCHIVED;

        static final List<EventEnvelope> TRIGGERED = new CopyOnWriteArrayList<>();

        static final List<EventEnvelope> ARCHIVED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itWardChainCaptureQueues(MessagingGovernance governance) {
            List<org.springframework.amqp.core.Declarable> declared = new java.util.ArrayList<>();
            declared.addAll(governance
                    .declareConsumerQueue(new ConsumerQueueSpec("it", IotMessagingConstants.EVENT_ALARM_TRIGGERED))
                    .getDeclarables());
            declared.addAll(governance
                    .declareConsumerQueue(
                            new ConsumerQueueSpec("it", WardMessagingConstants.EVENT_COLD_CHAIN_ALERT_ARCHIVED))
                    .getDeclarables());
            return new Declarables(declared);
        }

        @Bean
        ItWardChainCaptureListener itWardChainCaptureListener(EventEnvelopeCodec codec) {
            return new ItWardChainCaptureListener(codec);
        }
    }

    /** 捕获监听器本体（测试侧轻量：按事件类型分列表收帧，不登记 received_event）。 */
    static class ItWardChainCaptureListener {

        private final EventEnvelopeCodec codec;

        ItWardChainCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(queues = {ItCaptureConfig.Q_ALARM_TRIGGERED, ItCaptureConfig.Q_ALERT_ARCHIVED})
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            if (IotMessagingConstants.EVENT_ALARM_TRIGGERED.equals(envelope.eventType())) {
                ItCaptureConfig.TRIGGERED.add(envelope);
            } else if (WardMessagingConstants.EVENT_COLD_CHAIN_ALERT_ARCHIVED.equals(envelope.eventType())) {
                ItCaptureConfig.ARCHIVED.add(envelope);
            }
        }
    }

    /**
     * 注入兜底通道共享密钥（ingest 走 HTTP 兜底通道的真实鉴权面；FuyunStackITBase 的密钥三元组
     * 对本类继续生效——@DynamicPropertySource 基类/子类方法叠加处理）。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerFallbackProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.iot.fallback.token", () -> TEST_FALLBACK_TOKEN);
    }

    /** 设备档案 mapper：步骤①种子直插（IotAlarmClosedLoopIT 同款定稿） */
    private final IotDeviceMapper deviceMapper;

    /** 设备绑定 mapper：步骤①BOUND 绑定种子直插（告警绑定快照→输液呼叫患者/病区来源） */
    private final IotBindingMapper bindingMapper;

    /** 告警规则 mapper：LT 阈值规则种子直插（规则 id 后续步骤断言锚） */
    private final IotAlarmRuleMapper alarmRuleMapper;

    /** 呼叫路由规则 mapper：步骤①时段命中规则种子直插（route 端点解析断言锚） */
    private final WardCallRoutingRuleMapper routingRuleMapper;

    /** JDBC 模板：ward_call/冷链两列分人断言与 created_at 回填通道 */
    private final JdbcTemplate jdbcTemplate;

    /** 随机端口 HTTP 客户端：兜底通道 202 与呼叫动作/冷链登记 */
    private final TestRestTemplate restTemplate;

    /** 输液阈值规则 id（步骤①落行后回填，跨步共享） */
    private static long infusionRuleId;

    /** 红档告警业务号（步骤②捕获帧取得，ward_call.source_ref 与冷链 alarm_ref 跨步锚点） */
    private static String redAlarmNo;

    /** 冷链档案业务号（步骤⑤建档回填，步骤⑥ overdue 三态断言载体） */
    private static String archiveNo;

    /** admin 登录用户 id 十进制字符串（AuthTokenInterceptor 注入的操作者上下文实值，双人/留痕断言锚） */
    private static String adminOperator;

    /**
     * 构造器注入（@Autowired 显式声明可注入构造器，backend 宪法 A.1-7）：SpringExtension 从上下文
     * 解析各依赖（ward 的 mapper 由 app 侧 @MapperScan 按注解自动覆盖，WardConfig 注记同源）。
     *
     * @param deviceMapper      设备档案 mapper，非空
     * @param bindingMapper     设备绑定 mapper，非空
     * @param alarmRuleMapper   告警规则 mapper，非空
     * @param routingRuleMapper 呼叫路由规则 mapper，非空
     * @param jdbcTemplate      JDBC 模板，非空
     * @param restTemplate      随机端口 HTTP 客户端，非空
     */
    @Autowired
    WardCallColdChainIT(
            IotDeviceMapper deviceMapper,
            IotBindingMapper bindingMapper,
            IotAlarmRuleMapper alarmRuleMapper,
            WardCallRoutingRuleMapper routingRuleMapper,
            JdbcTemplate jdbcTemplate,
            TestRestTemplate restTemplate) {
        this.deviceMapper = deviceMapper;
        this.bindingMapper = bindingMapper;
        this.alarmRuleMapper = alarmRuleMapper;
        this.routingRuleMapper = routingRuleMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
    }

    /**
     * 步骤①：种子输液设备×2、设备1 BOUND 绑定、LT 阈值规则（INFUSION_SHORTAGE/6/1s/恢复带10）、
     * 呼叫路由规则（ward+NORMAL+当前时刻 ±1h 动态时段，命中确定性），并取 admin 操作者锚值。
     *
     * <p>设备档案与绑定 ward 必须一致（告警行 ward_id 取绑定快照，ward 监听器落行 ward_id 取
     * 载荷快照——IotAlarmEventListener 实核无病区过滤）；路由规则时段以判定时点动态构造，
     * 跨午夜由 WardCallServiceImpl.inTimeRange 的 start&gt;end 分支天然覆盖。
     */
    @Test
    @Order(1)
    @DisplayName("种子：输液设备×2 + BOUND 绑定 + LT 阈值规则 + 呼叫路由规则（直插定稿）")
    void seedDevicesBindingInfusionRuleAndRoutingRule() {
        insertDevice(INFUSION_DEVICE_ID);
        insertDevice(YELLOW_DEVICE_ID);

        IotBindingEntity binding = new IotBindingEntity();
        binding.setDeviceId(INFUSION_DEVICE_ID);
        binding.setPatientId(PATIENT_ID);
        binding.setVisitId(VISIT_ID);
        binding.setBedId(2001L);
        binding.setWardId(WARD_ID);
        binding.setBindType(BindType.FIXED);
        binding.setStatus(BindingStatus.BOUND);
        assertThat(bindingMapper.insert(binding)).as("BOUND 绑定种子插入成功").isEqualTo(1);

        infusionRuleId = insertInfusionRule();

        // 路由规则种子：时段=当前时刻±1h（起含终不含），目标链 JSONB 文本，任务转换开关置真验证透传
        OffsetDateTime now = OffsetDateTime.now();
        String timeRange =
                now.minusHours(1).format(HHMM) + "-" + now.plusHours(1).format(HHMM);
        WardCallRoutingRuleEntity rule = new WardCallRoutingRuleEntity();
        rule.setWardId(WARD_ID);
        rule.setCallType(CallType.NORMAL);
        rule.setTimeRange(timeRange);
        rule.setTargetChain("[\"nurse-station-1\",\"head-nurse\"]");
        rule.setTaskConvertFlag(true);
        assertThat(routingRuleMapper.insert(rule)).as("呼叫路由规则种子插入成功").isEqualTo(1);

        // 操作者锚值实取（AuthTokenInterceptor 注入十进制字符串化 userId，非登录名——双人断言锚）
        Long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM system.sys_user WHERE login_name = ?", Long.class, ADMIN_LOGIN_NAME);
        adminOperator = String.valueOf(adminId);
    }

    /**
     * 步骤②：输液告急自动落呼叫行全链——红档设备两批越限（"4" 起算回合、1.2s 后 "3" 达标触发，
     * LT 语义两批均须 &lt;6）：断言 iot_alarm ACTIVE 行 + q.it.iot.alarm.triggered 收帧 +
     * ward.ward_call 自动落 INFUSION/IOT/CREATED 行（source_ref=告警号、ward/patient/device 取
     * 载荷快照、bed_id 空——告警载荷无床位锚的 V1100 可空列面申报成立依据）；非红档设备
     * （"5.5"→"5.2"，越限告警在位但 >5ml 红档不足）：断言告警与事件均真实存在而呼叫行零落。
     *
     * <p>兜底通道 202 即 ingest 事务提交 + afterCommit 评估完成（同线程同步），告警行库态断言
     * 免轮询；ward 消费链与 MQ 捕获为异步，轮询/复核窗口等待。
     */
    @Test
    @Order(2)
    @DisplayName("输液告急全链：红档两批越限→ACTIVE 告警+triggered 帧→ward_call INFUSION 行自动落；非红档告警在位零落行")
    void infusionShortageAlarmAutoCreatesWardCall() throws Exception {
        // 红档回合：两批均越 LT 阈值（6），首批置越限标记起算，1.2s 后次批达标触发（触发值取末批 "3"）
        postFallback(INFUSION_DEVICE_ID, "4", "2026-09-27T06:00:00Z");
        postFallback(YELLOW_DEVICE_ID, "5.5", "2026-09-27T06:00:05Z");
        Thread.sleep(EPISODE_WAIT_MILLIS);
        postFallback(INFUSION_DEVICE_ID, "3", "2026-09-27T06:00:10Z");
        postFallback(YELLOW_DEVICE_ID, "5.2", "2026-09-27T06:00:15Z");

        Map<String, Object> redRow = awaitAlarmRow(INFUSION_DEVICE_ID, "ACTIVE", "红档告警行");
        assertThat(redRow.get("trigger_value")).as("红档触发值 = 次批越限原文").isEqualTo("3");
        assertThat(((Number) redRow.get("ward_id")).longValue())
                .as("告警病区 = 绑定快照路由")
                .isEqualTo(WARD_ID);
        assertThat(((Number) redRow.get("patient_id")).longValue())
                .as("告警患者 = 绑定快照冗余")
                .isEqualTo(PATIENT_ID);
        EventEnvelope redFrame =
                awaitCapturedForDevice(ItCaptureConfig.TRIGGERED, INFUSION_DEVICE_ID, "红档 triggered 帧");
        assertThat(redFrame.payload().path("metricCode").asText())
                .as("载荷指标编码 = 输液告急词（ward 监听器精确相等判定锚）")
                .isEqualTo(METRIC_CODE);
        assertThat(redFrame.payload().path("triggerValue").asText()).isEqualTo("3");
        assertThat(redFrame.payload().path("wardId").asLong()).isEqualTo(WARD_ID);
        redAlarmNo = redFrame.payload().path("alarmNo").asText();
        assertThat(redAlarmNo).as("捕获帧告警号与告警行勾稽").isEqualTo(redRow.get("alarm_no"));

        // ward 消费链落行断言（异步：轮询等待 IotAlarmEventListener 插入）
        awaitUntil("输液告急呼叫行自动落库（source_ref=" + redAlarmNo + "）", () -> wardCallOfSourceRef(redAlarmNo) != null);
        Map<String, Object> call = wardCallOfSourceRef(redAlarmNo);
        assertThat(((String) call.get("call_no")))
                .as("呼叫号前缀 CALL（WardSeqGate 签发）")
                .startsWith("CALL");
        assertThat(call.get("call_type")).isEqualTo("INFUSION");
        assertThat(call.get("source")).as("设备源呼叫仅经事件消费落行").isEqualTo("IOT");
        assertThat(call.get("status")).as("自动落行初始态 CREATED").isEqualTo("CREATED");
        assertThat(((Number) call.get("ward_id")).longValue()).isEqualTo(WARD_ID);
        assertThat(((Number) call.get("patient_id")).longValue()).as("患者取载荷快照").isEqualTo(PATIENT_ID);
        assertThat(call.get("device_id")).isEqualTo(INFUSION_DEVICE_ID);
        assertThat(call.get("bed_id")).as("告警载荷无床位锚，bed_id 空（V1100 可空列面申报）").isNull();
        assertThat(((Number) call.get("escalation_count")).intValue()).isZero();

        // 非红档负路径：告警与事件真实存在（链路活跃自证），但 triggerValue>5 不落呼叫行
        Map<String, Object> yellowRow = awaitAlarmRow(YELLOW_DEVICE_ID, "ACTIVE", "非红档告警行");
        assertThat(yellowRow.get("trigger_value")).isEqualTo("5.2");
        EventEnvelope yellowFrame =
                awaitCapturedForDevice(ItCaptureConfig.TRIGGERED, YELLOW_DEVICE_ID, "非红档 triggered 帧");
        String yellowAlarmNo = yellowFrame.payload().path("alarmNo").asText();
        Thread.sleep(ABSENCE_RECHECK_MILLIS);
        assertThat(wardCallOfSourceRef(yellowAlarmNo)).as("非红档（>5ml）不落呼叫行").isNull();
    }

    /**
     * 步骤③：呼叫状态机全合法迁移 + 合并取消 + 路由解析 + 设备源手工入口拒——
     * 主链 CREATED→ANSWERED→IN_PROGRESS→COMPLETED 逐跳（含 answered_at/processed_by/
     * result_summary/completed_at 留痕断言）；终态无出边（重完成/完成再取消 409 WD-1002）；
     * 合并取消（同床位新呼叫落行前旧 CREATED 行批量置 CANCELLED）；侧支 CREATED→TRANSFERRED→
     * ANSWERED→TRANSFERRED→CANCELLED 全回路（含 ANSWERED 不可人工取消 409）；CREATED→
     * IN_PROGRESS 非法 409；complete 缺摘要 400；route 时段命中 200（CAS 转接生效 + 目标链
     * 解析）；route 无命中 409 WD-1003 且转接 CAS 随事务回滚（状态停留 CREATED）；
     * CREATED→CANCELLED 人工取消出边；不存在呼叫 404 WD-1001。
     */
    @Test
    @Order(3)
    @DisplayName("呼叫状态机：全迁移+终态拒+合并取消+路由命中/回滚+IOT 源手工拒+404")
    void callStateMachineFullTransitionsMergeCancelAndRouting() {
        String token = loginToken(ADMIN_LOGIN_NAME);

        // 设备源手工入口被 DTO 拒绝（设备源呼叫只能经事件消费落行，防双通道重复）
        ObjectNode iotSource = createBody(BED_ROUTE_MISS, "NORMAL", "IOT");
        ResponseEntity<String> rejected = restTemplate.exchange(
                "/api/v1/ward/ward-calls", HttpMethod.POST, new HttpEntity<>(iotSource, bearer(token)), String.class);
        assertThat(rejected.getStatusCode().value())
                .as("source=IOT 手工创建应 400，实况：%s", rejected.getBody())
                .isEqualTo(400);

        // 合并取消：同床位先建 A 再建 B，A 自动置 CANCELLED
        String callA =
                createCall(token, BED_MERGE, "NORMAL", "BEDSIDE").path("callNo").asText();
        String callB = createCall(token, BED_MERGE, "NORMAL", "PATIENT_PAD")
                .path("callNo")
                .asText();
        assertThat(getCall(token, callA).path("status").asText())
                .as("同床位旧呼叫被合并取消")
                .isEqualTo("CANCELLED");
        assertThat(wardCallRow(callA).get("updated_by")).as("合并取消操作者留痕 = 登录用户").isEqualTo(adminOperator);

        // 主链逐跳：CREATED→ANSWERED→IN_PROGRESS→COMPLETED
        assertThat(action(token, callB, "answer").path("status").asText()).isEqualTo("ANSWERED");
        assertThat(wardCallRow(callB).get("answered_at")).as("应答时刻落行").isNotNull();
        assertThat(action(token, callB, "progress").path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(wardCallRow(callB).get("processed_by")).as("处理人留痕 = 登录用户").isEqualTo(adminOperator);
        ObjectNode completeBody = objectMapper.createObjectNode().put("resultSummary", "IT 呼叫处理完毕");
        ResponseEntity<String> completed = restTemplate.exchange(
                "/api/v1/ward/ward-calls/" + callB + "/complete",
                HttpMethod.POST,
                new HttpEntity<>(completeBody, bearer(token)),
                String.class);
        assertThat(completed.getStatusCode().value())
                .as("完成应 200，实况：%s", completed.getBody())
                .isEqualTo(200);
        JsonNode completedVo = toNode(completed.getBody());
        assertThat(completedVo.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completedVo.path("resultSummary").asText()).as("结果摘要出网").isEqualTo("IT 呼叫处理完毕");
        assertThat(wardCallRow(callB).get("completed_at")).as("完成时刻落行").isNotNull();

        // 终态无出边：重完成/取消均 409 WD-1002
        ResponseEntity<String> reComplete = restTemplate.exchange(
                "/api/v1/ward/ward-calls/" + callB + "/complete",
                HttpMethod.POST,
                new HttpEntity<>(completeBody, bearer(token)),
                String.class);
        assertThat(reComplete.getStatusCode().value()).as("终态重完成被拒").isEqualTo(409);
        assertThat(toNode(reComplete.getBody()).path("errorCode").asText())
                .as("状态机违例码位 WD-1002")
                .isEqualTo("WD-1002");
        assertThat(actionStatus(token, callB, "cancel")).as("终态取消被拒 409").isEqualTo(409);

        // 侧支回路：CREATED→TRANSFERRED→ANSWERED（answer 端点回路）→TRANSFERRED→CANCELLED
        String callC = createCall(token, BED_TRANSFER, "EMERGENCY", "BRROOM")
                .path("callNo")
                .asText();
        assertThat(action(token, callC, "transfer").path("status").asText()).isEqualTo("TRANSFERRED");
        assertThat(action(token, callC, "answer").path("status").asText())
                .as("转接侧支 answer 回路 TRANSFERRED→ANSWERED")
                .isEqualTo("ANSWERED");
        assertThat(actionStatus(token, callC, "cancel"))
                .as("ANSWERED 不可人工取消 409")
                .isEqualTo(409);
        assertThat(action(token, callC, "transfer").path("status").asText()).isEqualTo("TRANSFERRED");
        assertThat(action(token, callC, "cancel").path("status").asText())
                .as("TRANSFERRED→CANCELLED 人工取消出边")
                .isEqualTo("CANCELLED");

        // 非法迁移与缺参：CREATED→IN_PROGRESS 409；complete 空白摘要 400（@NotBlank 校验面）
        String callD = createCall(token, BED_ROUTE_HIT, "NORMAL", "NURSE_PAD")
                .path("callNo")
                .asText();
        assertThat(actionStatus(token, callD, "progress"))
                .as("CREATED 直接 progress 被拒 409")
                .isEqualTo(409);
        ResponseEntity<String> blankComplete = restTemplate.exchange(
                "/api/v1/ward/ward-calls/" + callD + "/complete",
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.createObjectNode().put("resultSummary", "   "), bearer(token)),
                String.class);
        assertThat(blankComplete.getStatusCode().value()).as("空白摘要完成被拒 400").isEqualTo(400);

        // route 时段命中：CAS 转接 + 目标链解析一并返回
        ResponseEntity<String> routed = restTemplate.exchange(
                "/api/v1/ward/ward-calls/" + callD + "/route",
                HttpMethod.POST,
                new HttpEntity<>(bearer(token)),
                String.class);
        assertThat(routed.getStatusCode().value())
                .as("时段命中 route 应 200，实况：%s", routed.getBody())
                .isEqualTo(200);
        JsonNode routeVo = toNode(routed.getBody());
        assertThat(routeVo.path("callNo").asText()).isEqualTo(callD);
        assertThat(routeVo.path("targetChain").size()).as("目标链两环解析").isEqualTo(2);
        assertThat(routeVo.path("targetChain").get(0).asText()).isEqualTo("nurse-station-1");
        assertThat(routeVo.path("taskConvertFlag").asBoolean()).as("规则开关透传").isTrue();
        assertThat(wardCallRow(callD).get("status")).as("route 内嵌转接生效").isEqualTo("TRANSFERRED");

        // route 无命中规则：409 WD-1003 且同事务 CAS 转接整体回滚（状态停留 CREATED）
        String callE = createCall(token, BED_ROUTE_MISS, "SERVICE", "NURSE_PAD")
                .path("callNo")
                .asText();
        ResponseEntity<String> miss = restTemplate.exchange(
                "/api/v1/ward/ward-calls/" + callE + "/route",
                HttpMethod.POST,
                new HttpEntity<>(bearer(token)),
                String.class);
        assertThat(miss.getStatusCode().value()).as("SERVICE 型无规则 route 应 409").isEqualTo(409);
        assertThat(toNode(miss.getBody()).path("errorCode").asText())
                .as("路由未配置码位 WD-1003")
                .isEqualTo("WD-1003");
        assertThat(wardCallRow(callE).get("status")).as("无命中回滚：转接不生效").isEqualTo("CREATED");
        assertThat(action(token, callE, "cancel").path("status").asText())
                .as("CREATED→CANCELLED 人工取消出边")
                .isEqualTo("CANCELLED");

        // 不存在呼叫：GET 详情 404 WD-1001
        ResponseEntity<String> missing = restTemplate.exchange(
                "/api/v1/ward/ward-calls/CALL0000000099999",
                HttpMethod.GET,
                new HttpEntity<>(bearer(token)),
                String.class);
        assertThat(missing.getStatusCode().value()).as("不存在呼叫应 404").isEqualTo(404);
        assertThat(toNode(missing.getBody()).path("errorCode").asText())
                .as("呼叫不存在码位 WD-1001")
                .isEqualTo("WD-1001");
    }

    /**
     * 步骤④：升级=读时惰性——创建后经 JDBC 回填 created_at=now−310s（越过 300s 常量阈值），
     * GET 详情触发 casEscalate：escalation_count=1 且状态不变仍可应答；二次 GET 不重发
     * （escalation_count=0 旧值限定兜底防重发语义）。GET 分页读路径同承载：过滤页内出现同判。
     */
    @Test
    @Order(4)
    @DisplayName("升级读时惰性：created_at 回填 310s 后 GET 详情递增 1 且不重发，分页读路径同承载")
    void escalationIsLazyCasOnReadPath() {
        String token = loginToken(ADMIN_LOGIN_NAME);
        String callF = createCall(token, BED_ESCALATE, "NORMAL", "BEDSIDE")
                .path("callNo")
                .asText();
        // 数据库写操作：回填创建时刻越过升级时限（IT 内确定性触发读时惰性升级，免挂钟等待）
        jdbcTemplate.update(
                "UPDATE ward.ward_call SET created_at = now() - interval '" + BACKDATE_SECONDS + "' WHERE call_no = ?",
                callF);

        JsonNode escalated = getCall(token, callF);
        assertThat(escalated.path("escalationCount").asInt()).as("首读触发升级 CAS").isEqualTo(1);
        assertThat(escalated.path("status").asText()).as("升级不改状态仍可应答").isEqualTo("CREATED");
        assertThat(wardCallRow(callF).get("updated_by"))
                .as("升级 CAS 操作者留痕（读路径登录用户）")
                .isEqualTo(adminOperator);

        JsonNode secondRead = getCall(token, callF);
        assertThat(secondRead.path("escalationCount").asInt())
                .as("DB 字段防重发：二次读不再递增")
                .isEqualTo(1);

        // 分页读路径承载复核：升级行在 status=CREATED 过滤页内可见且计数不回退
        ResponseEntity<String> pageResp = restTemplate.exchange(
                "/api/v1/ward/ward-calls?wardId=" + WARD_ID + "&status=CREATED&page=0&size=50",
                HttpMethod.GET,
                new HttpEntity<>(bearer(token)),
                String.class);
        assertThat(pageResp.getStatusCode().value())
                .as("呼叫分页应 200，实况：%s", pageResp.getBody())
                .isEqualTo(200);
        List<JsonNode> createdRows = new java.util.ArrayList<>();
        toNode(pageResp.getBody()).path("content").forEach(createdRows::add);
        assertThat(createdRows.stream()
                        .map(node -> node.path("callNo").asText())
                        .toList())
                .as("升级行出现在 CREATED 过滤页")
                .contains(callF);
        assertThat(createdRows)
                .filteredOn(node -> redAlarmNo.equals(node.path("sourceRef").asText(null)))
                .as("步骤②输液告急自动行同页承载（ward 监听器落行与手工行同 CREATED 状态面）")
                .isNotEmpty();
    }

    /**
     * 步骤⑤：冷链建档 + 告警处置双人校验 + 归档事件真实送达——建档 VO 携 overdue=true（当日零
     * 巡检天然逾期）；ALARM_HANDLE 缺 alarmRef/缺 secondOperator 各 400 WD-1005；档案不存在
     * 404 WD-1004；登记成功断言记录行 recorded_by（登录上下文）与 second_operator（请求体）
     * 两列分属两人；q.it.ward.cold-chain.alert-archived 真实收帧且六组件（archiveNo/recordNo/
     * alarmRef/purpose/handledBy/handledAt）逐项锚定——Task 12 单测敞口的同事务 publishEvent→
     * AFTER_COMMIT 出 MQ 组合路径 IT 兜底断言。
     */
    @Test
    @Order(5)
    @DisplayName("冷链双人处置：建档逾期注记+WD-1005 双人校验+WD-1004 档案缺+alert-archived 帧六组件锚定")
    void coldChainDualOperatorHandlingCapturesArchivedEvent() {
        String token = loginToken(ADMIN_LOGIN_NAME);

        ObjectNode archiveBody = objectMapper.createObjectNode();
        archiveBody
                .put("purpose", "VACCINE")
                .put("deviceId", COLD_CHAIN_DEVICE_ID)
                .put("tempRangeType", "COOL");
        archiveBody.put("inventoryDigest", "2-8℃ 疫苗冰箱 A 存量 120 支");
        JsonNode created = postJson("/api/v1/ward/cold-chain/archives", bearer(token), archiveBody);
        archiveNo = created.path("archiveNo").asText();
        assertThat(archiveNo).as("档案号前缀 ARCH（WardSeqGate 签发）").startsWith("ARCH");
        assertThat(created.path("overdue").asBoolean()).as("新建当日零巡检天然逾期").isTrue();

        // 双人校验负路径：缺 alarmRef / 缺 secondOperator 各 400 WD-1005；档案不存在 404 WD-1004
        ObjectNode missingAlarm = objectMapper.createObjectNode();
        missingAlarm.put("recordType", "ALARM_HANDLE").put("secondOperator", "it-second-nurse");
        assertThat(postStatus(token, "/api/v1/ward/cold-chain/archives/" + archiveNo + "/records", missingAlarm))
                .as("ALARM_HANDLE 缺 alarmRef 被拒 400")
                .isEqualTo(400);
        ObjectNode missingSecond = objectMapper.createObjectNode();
        missingSecond.put("recordType", "ALARM_HANDLE").put("alarmRef", redAlarmNo);
        ResponseEntity<String> missingSecondResp = recordRequest(token, archiveNo, missingSecond);
        assertThat(missingSecondResp.getStatusCode().value()).as("缺第二人被拒 400").isEqualTo(400);
        assertThat(toNode(missingSecondResp.getBody()).path("errorCode").asText())
                .as("双人校验码位 WD-1005")
                .isEqualTo("WD-1005");
        ObjectNode validHandle = alarmHandleBody(redAlarmNo, "it-second-nurse");
        assertThat(postStatus(token, "/api/v1/ward/cold-chain/archives/ARCH0000000099999/records", validHandle))
                .as("档案不存在应 404")
                .isEqualTo(404);

        // 处置登记成功：双人两列分属两人（operator 来自 Bearer 登录上下文，secondOperator 请求体显式）
        ResponseEntity<String> handled = recordRequest(token, archiveNo, validHandle);
        assertThat(handled.getStatusCode().value())
                .as("告警处置登记应 200，实况：%s", handled.getBody())
                .isEqualTo(200);
        JsonNode recordVo = toNode(handled.getBody());
        String recordNo = recordVo.path("recordNo").asText();
        assertThat(recordNo).as("记录号前缀 CCR（WardSeqGate 签发）").startsWith("CCR");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT record_type, alarm_ref, second_operator, recorded_by FROM ward.cold_chain_record WHERE record_no = ?",
                recordNo);
        assertThat(row.get("record_type")).isEqualTo("ALARM_HANDLE");
        assertThat(row.get("alarm_ref")).as("告警号回溯锚 = 步骤②红档告警").isEqualTo(redAlarmNo);
        assertThat(row.get("recorded_by")).as("登记人 = 登录用户 id（非登录名，拦截器实核）").isEqualTo(adminOperator);
        assertThat(row.get("second_operator")).as("第二人 = 请求体显式值").isEqualTo("it-second-nurse");
        assertThat(((String) adminOperator)).as("双人两列确属两人").isNotEqualTo("it-second-nurse");

        // 归档事件真实送达：治理捕获队列收帧，载荷六组件逐项锚定
        EventEnvelope archived = awaitCaptured(ItCaptureConfig.ARCHIVED, 1, "alert-archived 帧");
        JsonNode payload = archived.payload();
        assertThat(archived.eventType()).isEqualTo(WardMessagingConstants.EVENT_COLD_CHAIN_ALERT_ARCHIVED);
        assertThat(payload.path("archiveNo").asText()).isEqualTo(archiveNo);
        assertThat(payload.path("recordNo").asText()).isEqualTo(recordNo);
        assertThat(payload.path("alarmRef").asText()).isEqualTo(redAlarmNo);
        assertThat(payload.path("purpose").asText()).as("用途取归属档案回填").isEqualTo("VACCINE");
        assertThat(payload.path("handledBy").asText()).as("处置人 = 主操作者登录用户").isEqualTo(adminOperator);
        assertThat(payload.path("handledAt").isNull()).as("处置时刻非空").isFalse();

        // 记录列表随行出网（倒序承载面：当前仅 1 行）
        ResponseEntity<String> listResp = restTemplate.exchange(
                "/api/v1/ward/cold-chain/archives/" + archiveNo + "/records",
                HttpMethod.GET,
                new HttpEntity<>(bearer(token)),
                String.class);
        assertThat(listResp.getStatusCode().value()).isEqualTo(200);
        JsonNode list = toNode(listResp.getBody());
        assertThat(list.isArray()).as("记录列表为 JSON 数组").isTrue();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("recordNo").asText()).isEqualTo(recordNo);
    }

    /**
     * 步骤⑥：巡检 overdue 三态判定（每日≥2 次且最近一次 ≤6h）——当日 0 巡检 true、1 次 true、
     * 2 次且末次新鲜 false（INSPECTION 登记 recordedAt 服务端 now 生成，两笔同刻落库即满足，
     * 无需改库）；DEVIATION 不入巡检聚合仍 false；分页面随行携带 overdue=false 注记；
     * INSPECTION/DEVIATION 登记零事件——归档捕获帧计数维持步骤⑤的 1。
     */
    @Test
    @Order(6)
    @DisplayName("巡检 overdue：0 次/1 次逾期、2 次新鲜不逾期、DEVIATION 不改判、非处置类型零事件")
    void inspectionOverdueThreeStatesAndNoEventForNonAlarmHandle() throws Exception {
        String token = loginToken(ADMIN_LOGIN_NAME);

        // 当日 0 次巡检：详情面逾期（ALARM_HANDLE 不计入巡检聚合）
        assertThat(getArchive(token, archiveNo).path("overdue").asBoolean()).isTrue();

        // 1 次巡检：仍不足每日 2 次基线
        registerRecord(token, "INSPECTION", "{\"reading\":\"4.2℃\"}");
        assertThat(getArchive(token, archiveNo).path("overdue").asBoolean())
                .as("1 次巡检仍逾期")
                .isTrue();

        // 2 次巡检且末次新鲜（now 同刻落库）：满足双基线不逾期
        registerRecord(token, "INSPECTION", "{\"reading\":\"4.1℃\"}");
        assertThat(getArchive(token, archiveNo).path("overdue").asBoolean())
                .as("2 次新鲜巡检不逾期")
                .isFalse();

        // DEVIATION 不入巡检聚合：判定不因偏差登记翻转
        registerRecord(token, "DEVIATION", "{\"desc\":\"冰箱门封老化\"}");
        assertThat(getArchive(token, archiveNo).path("overdue").asBoolean())
                .as("偏差登记不改巡检判定")
                .isFalse();

        // 分页读路径随行注记：本档案在 VACCINE 过滤页内 overdue=false
        ResponseEntity<String> pageResp = restTemplate.exchange(
                "/api/v1/ward/cold-chain/archives?purpose=VACCINE&page=0&size=50",
                HttpMethod.GET,
                new HttpEntity<>(bearer(token)),
                String.class);
        assertThat(pageResp.getStatusCode().value())
                .as("档案分页应 200，实况：%s", pageResp.getBody())
                .isEqualTo(200);
        JsonNode archiveRow = null;
        for (JsonNode node : toNode(pageResp.getBody()).path("content")) {
            if (archiveNo.equals(node.path("archiveNo").asText())) {
                archiveRow = node;
                break;
            }
        }
        assertThat(archiveRow).as("本档案在 VACCINE 过滤页内可见").isNotNull();
        assertThat(archiveRow.path("overdue").asBoolean()).as("分页随行 overdue 注记").isFalse();

        // 记录列表：1 处置 + 2 巡检 + 1 偏差共 4 行
        ResponseEntity<String> listResp = restTemplate.exchange(
                "/api/v1/ward/cold-chain/archives/" + archiveNo + "/records",
                HttpMethod.GET,
                new HttpEntity<>(bearer(token)),
                String.class);
        assertThat(toNode(listResp.getBody()).size()).as("记录累计 4 行").isEqualTo(4);

        // 零事件复核：INSPECTION/DEVIATION 登记不发布归档事件，捕获帧计数维持 1
        Thread.sleep(ABSENCE_RECHECK_MILLIS);
        assertThat(ItCaptureConfig.ARCHIVED)
                .as("INSPECTION/DEVIATION 零事件（帧计数不增）")
                .hasSize(1);
    }

    // ---------------------------------------------------------------- 种子与断言助手

    /** 输液设备种子直插（audit 列走库端默认；ward 兜底路由承载）。 */
    private void insertDevice(String deviceId) {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(deviceId);
        device.setDeviceName("IT 病房链路种子设备");
        device.setDeviceType("infusion-pump");
        device.setAccessMode(DeviceAccessMode.A);
        device.setWardId(WARD_ID);
        device.setStatus(DeviceStatus.ONLINE);
        assertThat(deviceMapper.insert(device)).as("设备种子插入成功：" + deviceId).isEqualTo(1);
    }

    /**
     * 输液阈值规则种子直插（LT 6 + 持续 1s + 恢复带 10：越限=余量<6ml，触发值 ≤5 即红档）
     * 并回读雪花 id。
     *
     * @return 落行后的规则 id（后续步骤断言锚）
     */
    private long insertInfusionRule() {
        IotAlarmRuleEntity rule = new IotAlarmRuleEntity();
        rule.setRuleName("IT 输液告急规则");
        rule.setRuleType(AlarmRuleType.THRESHOLD);
        rule.setMetricCode(METRIC_CODE);
        rule.setCompareOp(ThresholdOp.LT);
        rule.setThresholdValue(java.math.BigDecimal.valueOf(6));
        rule.setDurationSecs(DURATION_SECS);
        rule.setRecoveryBand(java.math.BigDecimal.valueOf(10));
        rule.setAlarmLevel(AlarmLevel.CRITICAL);
        rule.setEnabled(true);
        assertThat(alarmRuleMapper.insert(rule)).as("输液阈值规则种子插入成功").isEqualTo(1);
        return rule.getId();
    }

    /** 兜底通道受理（202；occurredAt 固定历史时点防时钟偏差 SUSPECT 干扰触发值断言）。 */
    private void postFallback(String deviceId, String value, String occurredAt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("deviceId", deviceId)
                .put("metricCode", METRIC_CODE)
                .put("value", value)
                .put("occurredAt", occurredAt);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Iot-Fallback-Token", TEST_FALLBACK_TOKEN);
        ResponseEntity<String> resp =
                restTemplate.postForEntity("/ingest/iotda-fallback", new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().value())
                .as("兜底受理应 202（device=%s value=%s），实况：%s", deviceId, value, resp.getBody())
                .isEqualTo(202);
    }

    /** Bearer 请求头（呼叫/冷链端点鉴权输入）。 */
    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    /** 手工呼叫创建请求体组装（ward/bed/patient 必填组件 + 类型/来源）。 */
    private ObjectNode createBody(long bedId, String callType, String source) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("wardId", WARD_ID)
                .put("bedId", bedId)
                .put("patientId", PATIENT_ID)
                .put("callType", callType)
                .put("source", source);
        return body;
    }

    /** 手工创建呼叫并返回 VO（成功路径 200）。 */
    private JsonNode createCall(String token, long bedId, String callType, String source) {
        JsonNode resp = postJson("/api/v1/ward/ward-calls", bearer(token), createBody(bedId, callType, source));
        assertThat(resp.path("status").asText()).as("新建呼叫初始态 CREATED").isEqualTo("CREATED");
        return resp;
    }

    /** 呼叫无体动作 POST（answer/progress/transfer/cancel 端点），返回原始响应供状态码断言。 */
    private ResponseEntity<String> actionRequest(String token, String callNo, String action) {
        return restTemplate.exchange(
                "/api/v1/ward/ward-calls/" + callNo + "/" + action,
                HttpMethod.POST,
                new HttpEntity<>(bearer(token)),
                String.class);
    }

    /** 呼叫无体动作 POST 成功面（200 + VO 出网）。 */
    private JsonNode action(String token, String callNo, String action) {
        ResponseEntity<String> resp = actionRequest(token, callNo, action);
        assertThat(resp.getStatusCode().value())
                .as("动作 %s 应 200（callNo=%s），实况：%s", action, callNo, resp.getBody())
                .isEqualTo(200);
        return toNode(resp.getBody());
    }

    /** 呼叫无体动作 POST 状态码面（负路径断言专用）。 */
    private int actionStatus(String token, String callNo, String action) {
        return actionRequest(token, callNo, action).getStatusCode().value();
    }

    /** 呼叫详情 GET（读时惰性升级承载面）。 */
    private JsonNode getCall(String token, String callNo) {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/ward/ward-calls/" + callNo, HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(resp.getStatusCode().value())
                .as("呼叫详情应 200（callNo=%s）", callNo)
                .isEqualTo(200);
        return toNode(resp.getBody());
    }

    /** 冷链记录登记请求（状态码+体双可用面）。 */
    private ResponseEntity<String> recordRequest(String token, String targetArchiveNo, ObjectNode body) {
        return restTemplate.exchange(
                "/api/v1/ward/cold-chain/archives/" + targetArchiveNo + "/records",
                HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)),
                String.class);
    }

    /** 冷链记录登记状态码面（负路径专用）。 */
    private int postStatus(String token, String path, ObjectNode body) {
        return restTemplate
                .exchange(path, HttpMethod.POST, new HttpEntity<>(body, bearer(token)), String.class)
                .getStatusCode()
                .value();
    }

    /** 告警处置登记请求体组装（双人：alarmRef + secondOperator 显式）。 */
    private ObjectNode alarmHandleBody(String alarmRef, String secondOperator) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("recordType", "ALARM_HANDLE").put("alarmRef", alarmRef).put("secondOperator", secondOperator);
        body.put("content", "{\"measure\":\"断电除霜处置完成\"}");
        return body;
    }

    /** 巡检/偏差登记（成功 200）。 */
    private void registerRecord(String token, String recordType, String contentJson) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("recordType", recordType).put("content", contentJson);
        ResponseEntity<String> resp = recordRequest(token, archiveNo, body);
        assertThat(resp.getStatusCode().value())
                .as("%s 登记应 200，实况：%s", recordType, resp.getBody())
                .isEqualTo(200);
    }

    /** 冷链档案详情 GET（overdue 注记随行承载面）。 */
    private JsonNode getArchive(String token, String targetArchiveNo) {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/ward/cold-chain/archives/" + targetArchiveNo,
                HttpMethod.GET,
                new HttpEntity<>(bearer(token)),
                String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return toNode(resp.getBody());
    }

    /** 无体响应解析兜底（204/空体回 MISSING 单例，IotAlarmClosedLoopIT 同型收口）。 */
    private JsonNode toNode(String body) {
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 呼叫行整行回读（ward.ward_call 自然键定位，库态留痕断言通道）。 */
    private Map<String, Object> wardCallRow(String callNo) {
        return jdbcTemplate.queryForMap("SELECT * FROM ward.ward_call WHERE call_no = ?", callNo);
    }

    /** 告警号勾稽呼叫行（source_ref=告警号；无行返回 null 驱动轮询/缺席断言）。 */
    private Map<String, Object> wardCallOfSourceRef(String sourceRef) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM ward.ward_call WHERE source_ref = ? AND call_type = 'INFUSION'", sourceRef);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 设备维度活跃告警行等待并整行返回（兜底通道同线程 afterCommit，库态通常即时；轮询兜底抖动）。 */
    private Map<String, Object> awaitAlarmRow(String deviceId, String status, String description) {
        awaitUntil(description + "（rule=" + infusionRuleId + "，device=" + deviceId + "）", () -> !jdbcTemplate
                .queryForList(
                        "SELECT * FROM iot.iot_alarm WHERE rule_id = ? AND device_id = ? AND status = ?",
                        infusionRuleId,
                        deviceId,
                        status)
                .isEmpty());
        return jdbcTemplate.queryForMap(
                "SELECT * FROM iot.iot_alarm WHERE rule_id = ? AND device_id = ? AND status = ?",
                infusionRuleId,
                deviceId,
                status);
    }

    /** 等待指定设备的 triggered 帧出现并返回（捕获列表按载荷 deviceId 过滤勾稽）。 */
    private EventEnvelope awaitCapturedForDevice(List<EventEnvelope> captured, String deviceId, String description) {
        awaitUntil(description + "收帧达标（device=" + deviceId + "）", () -> captured.stream()
                .anyMatch(env -> deviceId.equals(env.payload().path("deviceId").asText())));
        return captured.stream()
                .filter(env -> deviceId.equals(env.payload().path("deviceId").asText()))
                .findFirst()
                .orElseThrow();
    }

    /** 等待捕获列表达到目标帧数并返回首帧（MQ 异步消费的确定性等待）。 */
    private EventEnvelope awaitCaptured(List<EventEnvelope> captured, int expectedSize, String description) {
        awaitUntil(description + "收帧达标（" + expectedSize + "）", () -> captured.size() >= expectedSize);
        return captured.get(0);
    }

    /** 轮询等待业务条件成立（超时后最终复核一次以输出断言上下文，IotAlarmClosedLoopIT 同款）。 */
    private static void awaitUntil(String description, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + CONSUME_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }
}
