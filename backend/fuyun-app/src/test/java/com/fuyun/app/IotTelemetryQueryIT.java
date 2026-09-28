package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * 遥测时序查询验收锚点 IT（FU-M14-06，P2 PR-2 Task 18）：三档路由查询 + 非法时窗拒绝 +
 * latest 快照端点 + 连续聚合行物化存在，全部真栈（TimescaleDB cagg 真实刷新）。
 *
 * <p>业务意图：三档路由（≤24h 明细 / 超 24h 或显式档位连续聚合 / 超 90 天强制 1 小时聚合）是
 * 保护数据库的核心查询分级（14-iot §3.2），单测以 mock mapper 验证路由分支，本类以真实
 * cagg_1min/cagg_1h 视图端到端兑现：五步断言按 @Order 串联——①种子（兜底通道注入三个分钟对齐
 * 数值行 + 一个 latest 新鲜行）；②明细档 raw 查询（3 点、sampleCount=1、时刻升序）；③强制聚合
 * 档（构造 25h 窗自动走 cagg_1min：两桶点、首桶 sampleCount=2 且 avg=75；构造 91 天窗强制
 * cagg_1h：单桶 sampleCount=3）；④非法时窗 from≥to → 400 IOT-1019；⑤latest 快照端点（兜底
 * ingest 后 AlarmEngine 写入面即时可查，value 精确等值 + occurredAt 非空）+ cagg 行存在断言
 * （Testcontainers 环境后台策略作业不可依赖，显式 {@code CALL refresh_continuous_aggregate}
 * （存储过程形态，实测 2.29.2 SELECT 调用报语法错）回刷物化窗口后目录行回读）。
 *
 * <p>时点设计申报：种子行 occurredAt 取「当前整分 −3h」系内——既在明细保留窗口（90 天）与
 * 压缩策略热区（不触压缩 chunk，回刷确定性）、又稳定早于 cagg 策略 end_offset(10min)，显式
 * 回刷窗口合法；latest 行取 −20s 保证时间合理性步 GOOD 且快照 TTL(10m) 内可查。指标编码全部
 * 词表外直通（V1008 种子规则不命中，零告警侧效应）。
 *
 * <p>容器三件套与 {@link IotTelemetryPipelineIT} 完全同款（类级独占 + @ServiceConnection）；
 * 登录/POST 助手复用 {@link FuyunStackITBase}。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotTelemetryQueryIT extends FuyunStackITBase {

    /** TimescaleDB 容器：cagg_1min/cagg_1h 真实物化断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：latest 快照读写载体（AlarmEngine 写入面 + 查询兜底读面） */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：上下文完整装配所需（事件发布构件），本类无事件断言 */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假兜底共享密钥（fuyun.iot.fallback.token，仅具 IT 意义，与任何真实凭证无关） */
    private static final String TEST_FALLBACK_TOKEN = "it-iot-tq-fallback-token";

    /** 查询锚定设备号 */
    private static final String DEVICE_ID = "it-tq-001";

    /** 三档路由断言用指标编码（词表外直通） */
    private static final String METRIC = "vital.query-it";

    /** latest 端点断言用指标编码（独立行，快照值确定） */
    private static final String LATEST_METRIC = "vital.query-latest";

    /** 种子基准时刻：当前整分 −3h（分钟对齐，桶边界可预置；类级常量跨步共享） */
    private static final Instant BASE =
            Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(3, ChronoUnit.HOURS);

    /** 异步链路等待上限（ingest 后快照写入即时性兜底） */
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);

    /** 明细档三点种子值（同桶 70/80 + 次桶 90——聚合 avg 断言算术锚） */
    private static final String VALUE_1 = "70";

    private static final String VALUE_2 = "80";
    private static final String VALUE_3 = "90";
    private static final String LATEST_VALUE = "95";

    /**
     * 注入兜底通道共享密钥（种子/快照步骤直打 HTTP 兜底真实鉴权面；application.yml 该键空
     * 默认值 fail-closed，未注入则全数 401——A1 批次 IotNonNumericIngestIT 实证雷区，本类同型
     * 补装。基类密钥三元组的 @DynamicPropertySource 对本类继续生效——叠加处理）。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerFallbackProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.iot.fallback.token", () -> TEST_FALLBACK_TOKEN);
    }

    /** JDBC 模板：cagg 显式回刷与目录行回读通道 */
    private final JdbcTemplate jdbcTemplate;

    /** 随机端口 HTTP 客户端：GET 查询端点与兜底 POST */
    private final TestRestTemplate restTemplate;

    /**
     * 构造器注入（backend 宪法 A.1-7）。
     *
     * @param jdbcTemplate JDBC 模板，非空
     * @param restTemplate 随机端口 HTTP 客户端，非空
     */
    @Autowired
    IotTelemetryQueryIT(JdbcTemplate jdbcTemplate, TestRestTemplate restTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
    }

    /**
     * 步骤①：种子——分钟对齐三数值行（base+10s=70 / base+50s=80 同分钟桶，base+70s=90 次分钟
     * 桶）+ latest 新鲜行（−20s=95），全部经兜底通道同步入库（202 即落库）。
     */
    @Test
    @Order(1)
    @DisplayName("种子：分钟对齐三数值行 + latest 新鲜行（兜底通道同步入库）")
    void seedsAlignedTelemetryRows() {
        acceptFallback(METRIC, VALUE_1, BASE.plusSeconds(10));
        acceptFallback(METRIC, VALUE_2, BASE.plusSeconds(50));
        acceptFallback(METRIC, VALUE_3, BASE.plusSeconds(70));
        acceptFallback(
                LATEST_METRIC,
                LATEST_VALUE,
                Instant.now().minus(20, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * 步骤②：明细档——10 分钟窗 + granularity=raw → 3 点升序、sampleCount=1、值逐点对齐。
     */
    @Test
    @Order(2)
    @DisplayName("明细档：raw 10 分钟窗 → 3 点升序 sampleCount=1")
    void queriesDetailTierWithinShortWindow() {
        JsonNode points = getSeries(METRIC, BASE.minusSeconds(60), BASE.plusSeconds(300), "raw");
        assertThat(points).as("明细档 3 行全量返回").hasSize(3);
        assertThat(points.get(0).path("sampleCount").asInt()).as("raw 点样本数恒 1").isEqualTo(1);
        assertThat(points.get(0).path("last").decimalValue()).isEqualByComparingTo(new BigDecimal(VALUE_1));
        assertThat(points.get(1).path("last").decimalValue()).isEqualByComparingTo(new BigDecimal(VALUE_2));
        assertThat(points.get(2).path("last").decimalValue()).isEqualByComparingTo(new BigDecimal(VALUE_3));
        assertThat(points.get(0).path("time").asText())
                .isLessThan(points.get(2).path("time").asText());
    }

    /**
     * 步骤③：强制聚合档——显式回刷两视图后，25 小时窗（>24h 自动转 cagg_1min）→ 两分钟桶点、
     * 首桶 sampleCount=2 且 avg=75、次桶 sampleCount=1；91 天窗（>90d 强制 cagg_1h）→ 单小时桶
     * sampleCount=3、min=70/max=90。
     */
    @Test
    @Order(3)
    @DisplayName("强制聚合档：25h 窗走 cagg_1min（首桶 count=2 avg=75）；91d 窗强制 cagg_1h（count=3）")
    void forcesAggregateTiersByWindow() {
        refreshCagg("iot.cagg_1min", BASE.minus(Duration.ofMinutes(10)), BASE.plus(Duration.ofMinutes(10)));
        refreshCagg(
                "iot.cagg_1h",
                BASE.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1)),
                BASE.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(2)));

        JsonNode minute = getSeries(METRIC, BASE.minus(Duration.ofHours(25)), BASE.plus(Duration.ofMinutes(5)), null);
        assertThat(minute).as("25h 窗自动聚合为两分钟桶").hasSize(2);
        assertThat(minute.get(0).path("sampleCount").asInt()).as("首桶两点合并").isEqualTo(2);
        assertThat(minute.get(0).path("avg").decimalValue())
                .as("首桶 avg(70,80)=75")
                .isEqualByComparingTo("75");
        assertThat(minute.get(1).path("sampleCount").asInt()).isEqualTo(1);

        JsonNode hourly = getSeries(METRIC, BASE.minus(Duration.ofDays(91)), BASE.plus(Duration.ofMinutes(5)), null);
        assertThat(hourly).as("91d 窗强制 1 小时聚合，三点归一桶").hasSize(1);
        assertThat(hourly.get(0).path("sampleCount").asInt()).isEqualTo(3);
        assertThat(hourly.get(0).path("min").decimalValue()).isEqualByComparingTo(new BigDecimal(VALUE_1));
        assertThat(hourly.get(0).path("max").decimalValue()).isEqualByComparingTo(new BigDecimal(VALUE_3));
    }

    /**
     * 步骤④：非法时窗——from==to（半开区间空窗）→ 400 ProblemDetail errorCode=IOT-1019。
     */
    @Test
    @Order(4)
    @DisplayName("非法时窗：from≥to → 400 IOT-1019")
    void rejectsInvertedOrEmptyWindow() {
        String url = "/api/v1/iot/telemetry/series?scope=device&deviceId=" + DEVICE_ID + "&metricCode=" + METRIC
                + "&from=" + BASE + "&to=" + BASE + "&granularity=raw";
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(bearer(loginToken(ADMIN_LOGIN_NAME))), String.class);
        assertThat(resp.getStatusCode().value()).as("空窗应 400").isEqualTo(400);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("IOT-1019");
    }

    /**
     * 步骤⑤：latest 快照 + cagg 目录行——① GET /latest（设备+新鲜指标）value=95 精确等值 +
     * occurredAt 非空（时刻文本断言取 EmpiGovernanceIT isNotBlank 先例口径，序列化偏移形态不做
     * 字面锁定；AlarmEngine 写入面 → 查询读面同键 fy:iot:snapshot:latest:* 闭环）；
     * ② 显式回刷后 iot.cagg_1min 目录行存在（本设备本指标 ≥2 桶——"物化真实发生"的库端证据，
     * 不依赖后台策略作业调度）。
     */
    @Test
    @Order(5)
    @DisplayName("latest 快照端点与 cagg 物化行存在：value=95 回读一致 + cagg_1min 目录行 ≥2")
    void latestSnapshotAndMaterializedCaggRows() {
        String url = "/api/v1/iot/telemetry/latest?deviceId=" + DEVICE_ID + "&metricCode=" + LATEST_METRIC;
        JsonNode latest = exchangeJson(url);
        assertThat(latest.path("value").decimalValue())
                .as("latest 快照值 = 新鲜注入行")
                .isEqualByComparingTo(new BigDecimal(LATEST_VALUE));
        assertThat(latest.path("occurredAt").asText()).isNotBlank();

        Integer buckets = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.cagg_1min WHERE device_id = ? AND metric_code = ?",
                Integer.class,
                DEVICE_ID,
                METRIC);
        assertThat(buckets).as("显式回刷后 cagg_1min 物化桶行存在（≥2）").isGreaterThanOrEqualTo(2);
    }

    // ---------------------------------------------------------------- 端点与库面助手

    /** 兜底通道受理（202；与告警闭环 IT 同型，本类行由 HTTP 线程同步落库）。 */
    private void acceptFallback(String metricCode, String value, Instant occurredAt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("deviceId", DEVICE_ID)
                .put("metricCode", metricCode)
                .put("value", value)
                .put("occurredAt", occurredAt.toString());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Iot-Fallback-Token", TEST_FALLBACK_TOKEN);
        ResponseEntity<String> resp =
                restTemplate.postForEntity("/ingest/iotda-fallback", new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().value())
                .as("兜底受理应 202，实况：%s", resp.getBody())
                .isEqualTo(202);
    }

    /**
     * 连续聚合显式回刷（Testcontainers 环境策略作业不可依赖的确定性兜底，brief 冻结形态）。
     * 实测 timescaledb:2.29.2 中 refresh_continuous_aggregate 为存储过程（SELECT 调用报
     * 「is a procedure, use CALL」），改 CALL 形态；参数经显式类型转换避免驱动侧类型歧义。
     */
    private void refreshCagg(String caggName, Instant start, Instant end) {
        jdbcTemplate.update(
                "CALL refresh_continuous_aggregate(?::regclass, ?::timestamptz, ?::timestamptz)",
                caggName,
                start.toString(),
                end.toString());
    }

    /** GET series（granularity 可空=自动路由；from/to 取 ISO-8601 Z 串直传）。 */
    private JsonNode getSeries(String metricCode, Instant from, Instant to, String granularity) {
        StringBuilder url = new StringBuilder("/api/v1/iot/telemetry/series?scope=device&deviceId=")
                .append(DEVICE_ID)
                .append("&metricCode=")
                .append(metricCode)
                .append("&from=")
                .append(from)
                .append("&to=")
                .append(to);
        if (granularity != null) {
            url.append("&granularity=").append(granularity);
        }
        return exchangeJson(url.toString());
    }

    /** 带 Bearer 的 GET JSON（admin 真实登录全链）。 */
    private JsonNode exchangeJson(String url) {
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(bearer(loginToken(ADMIN_LOGIN_NAME))), String.class);
        assertThat(resp.getStatusCode().value())
                .as("GET 应 200，实况：%s %s", url, resp.getBody())
                .isEqualTo(200);
        return toNode(resp.getBody());
    }

    /** Bearer 请求头构造。 */
    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** 无体响应解析兜底（既有 IT 同型收口）。 */
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
}
