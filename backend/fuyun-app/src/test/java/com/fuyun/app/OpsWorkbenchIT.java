package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
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
 * 运营工作台聚合端到端验收 IT（批次 2 册 2）：两聚合端点全链（真实登录取令牌 → HTTP GET →
 * 四 Port 跨模块聚合 → Redis 快照缓存）真栈断言。
 *
 * <p>业务意图：零伪数据逐项指源——六格/趋势/候诊表/事件流的每个数字都对应 JdbcTemplate 播种
 * 的真实业务行。五步断言按 @Order 串联——①种子（门诊 visit 3+2 行两日、候诊票据 3 WAITING
 * 两科室+1 CANCELLED 干扰行、费用 PENDING 2+SETTLED 1+CANCELLED 1 行、调剂 CREATED/PICKING/
 * ISSUED 各 1 行、在院投影 2 在册+1 逻辑删行）；②overview 真值断言（六格逐字段+14 日趋势
 * 零填充与两日真值+候诊表降序）；③缓存回写与命中（Redis 键 TTL 5s 上界 + 二次 GET
 * generatedAt 不变=读路径不触库）；④events 五源断言（三主题指引+双源 4 事件+危急值恒空段带
 * 降级标志）；⑤未登录 401 鉴权面断言。
 *
 * <p>容器三件套与 {@link IotTelemetryQueryIT} 同款（类级独占 + @ServiceConnection，镜像 tag
 * 与 deploy compose 严格一致，backend 宪法 C.5-4）；登录/播种助手复用 {@link FuyunStackITBase}。
 * 时间口径：种子全部按北京钟面表达式（{@code (now() AT TIME ZONE 'Asia/Shanghai')}）锚定正午，
 * 容器 UTC 时区不使业务日漂移（TimeConstants.HEALTHCARE_TZ 同源红线）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpsWorkbenchIT extends FuyunStackITBase {

    /** TimescaleDB 容器：五表种子与四 Port 聚合的断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：overview 快照 TTL 5s 缓存载体 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：上下文完整装配所需（事件发布构件），本类无事件断言 */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 动态属性注入：token HMAC 启动 fail-fast 前置（基类已注入患者域加密键，本类仅补安全密钥） */
    @DynamicPropertySource
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_SECRET);
    }

    /** overview 快照缓存键（与 OpsConstants 同值断言——常量经模块依赖不在 test classpath 直接可见性内，字面量留痕） */
    private static final String SNAPSHOT_KEY = "fy:ops:snapshot:workbench:overview";

    /** 种子主键基座（19 位固定值，跨表互不冲突且不撞既有 IT 种子段） */
    private static final long SEED_ID_BASE = 9119000000000000000L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 步骤①：五表种子（全部北京钟面表达式锚定正午——容器 UTC 不使业务日漂移）。
     *
     * <p>计数设计：今日 visit=3（含 1 急诊）+昨日 visit=2；候诊 D01=2（最早 90 分钟前）+
     * D02=1，另 1 CANCELLED 干扰行不入面；费用今日 PENDING=2（3500+12500 分，事件行集源）+
     * SETTLED=1（10000 分，入收入）+CANCELLED=1（不入任何面）；调剂 CREATED=1+PICKING=1+
     * ISSUED=1（终态不入待配药面）；在院投影在册=2+逻辑删=1。
     */
    @Test
    @Order(1)
    @DisplayName("①五表种子：门诊/候诊/费用/调剂/在院投影按北京钟面锚定落库")
    void seedWorkbenchData() {
        // 北京钟面当日/昨日正午锚点（12:00 恒落当日内——深夜运行不漂移昨日）。
        // queryForObject 需要完整 SQL 语句：裸表达式缺 SELECT 前缀会被 PG 以
        // "syntax error at or near date_trunc" 拒绝执行，故表达式统一补全 SELECT 前缀
        String shanghaiNoonExpr =
                "(date_trunc('day', (now() AT TIME ZONE 'Asia/Shanghai')) + interval '12 hours') AT TIME ZONE 'Asia/Shanghai'";
        String todayNoon = "SELECT " + shanghaiNoonExpr;
        String yesterdayNoon = "SELECT (" + shanghaiNoonExpr + " - interval '1 day')";
        // 门诊就诊 5 行（今日 3 含急诊，昨日 2；visit_id O 型 14 位，uk_visit_id）
        jdbcTemplate.update(
                "INSERT INTO outpatient.visit (id, visit_id, patient_id, dept_code, visit_type, registered_at, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED'), (?, ?, ?, ?, ?, ?, 'REGISTERED'),"
                        + " (?, ?, ?, ?, ?, ?, 'REGISTERED'), (?, ?, ?, ?, ?, ?, 'REGISTERED'),"
                        + " (?, ?, ?, ?, ?, ?, 'REGISTERED')",
                /* 今日 G1 */ seedId(1),
                visitId(1),
                7001L,
                "D01",
                "GENERAL",
                jdbcTemplate.queryForObject(todayNoon, OffsetDateTime.class),
                /* 今日 G2 */ seedId(2),
                visitId(2),
                7002L,
                "D02",
                "GENERAL",
                jdbcTemplate.queryForObject(todayNoon, OffsetDateTime.class),
                /* 今日急诊 */ seedId(3),
                visitId(3),
                7003L,
                "D01",
                "EMERGENCY",
                jdbcTemplate.queryForObject(todayNoon, OffsetDateTime.class),
                /* 昨日 G1 */ seedId(4),
                visitId(4),
                7004L,
                "D01",
                "GENERAL",
                jdbcTemplate.queryForObject(yesterdayNoon, OffsetDateTime.class),
                /* 昨日 G2 */ seedId(5),
                visitId(5),
                7005L,
                "D02",
                "GENERAL",
                jdbcTemplate.queryForObject(yesterdayNoon, OffsetDateTime.class));
        // 候诊票据 4 行（3 WAITING 两科室 + 1 CANCELLED 干扰行；uk_ticket_visit 三元组唯一）
        jdbcTemplate.update(
                "INSERT INTO outpatient.queue_ticket (id, visit_id, queue_id, ticket_no, ticket_type, priority_score,"
                        + " queue_seq, queue_time, status) VALUES"
                        + " (?, ?, 'D01', 'A001', 'FIRST', 0, 1, now() - interval '90 minutes', 'WAITING'),"
                        + " (?, ?, 'D01', 'A002', 'FIRST', 0, 2, now() - interval '30 minutes', 'WAITING'),"
                        + " (?, ?, 'D02', 'A001', 'FIRST', 0, 1, now() - interval '10 minutes', 'WAITING'),"
                        + " (?, ?, 'D01', 'A003', 'FIRST', 0, 3, now() - interval '5 minutes', 'CANCELLED')",
                seedId(11),
                visitId(1),
                seedId(12),
                visitId(2),
                seedId(13),
                visitId(3),
                seedId(14),
                visitId(1));
        // 费用 4 行（PENDING×2=待支付事件+待结算计数、SETTLED×1=入收入、CANCELLED×1 全不入面；
        // uk_fee_billing_key 唯一；billing_date 北京钟面当日）
        insertFee(seedId(21), "FITF1001", "血常规", 3500L, "PENDING", null);
        insertFee(seedId(22), "FITF1002", "腹部彩超", 12500L, "PENDING", null);
        insertFee(seedId(23), "FITF1003", "挂号费", 10000L, "SETTLED", 990001L);
        insertFee(seedId(24), "FITF1004", "作废项目", 999L, "CANCELLED", null);
        // 调剂 3 行（CREATED/PICKING=待配药两面、ISSUED 终态不入面）
        jdbcTemplate.update(
                "INSERT INTO pharmacy.dispense (id, dispense_no, dispense_type, prescription_id, rx_no, patient_id,"
                        + " visit_id, storehouse, status, created_at) VALUES"
                        + " (?, 'FITD1000001', 'OUTPATIENT', 1, 'FITRX0001', 7001, ?, 'OUTP_PHARM', 'CREATED', now() - interval '20 minutes'),"
                        + " (?, 'FITD1000002', 'OUTPATIENT', 2, 'FITRX0002', 7002, ?, 'OUTP_PHARM', 'PICKING', now() - interval '10 minutes'),"
                        + " (?, 'FITD1000003', 'OUTPATIENT', 3, 'FITRX0003', 7003, ?, 'OUTP_PHARM', 'ISSUED', now() - interval '3 hours')",
                seedId(31),
                visitId(1),
                seedId(32),
                visitId(2),
                seedId(33),
                visitId(3));
        // 在院投影 3 行（2 在册 + 1 逻辑删——出院面不入在院计数）
        jdbcTemplate.update(
                "INSERT INTO nursing.nursing_ward_patient (id, ward_id, bed_no, patient_id, visit_id, patient_name,"
                        + " admitted_at, deleted) VALUES"
                        + " (?, 'W01', '01', 8001, 'I2026100100001', '投影甲', now() - interval '2 days', 0),"
                        + " (?, 'W01', '02', 8002, 'I2026100100002', '投影乙', now() - interval '1 day', 0),"
                        + " (?, 'W01', '03', 8003, 'I2026100100003', '投影丙', now() - interval '3 days', 1)",
                seedId(41),
                seedId(42),
                seedId(43));
    }

    /**
     * 单行费用播种（V602 非空列最小集；billing_date 恒北京钟面当日）。
     *
     * @param id        行主键
     * @param feeNo     费用编号（uk_fee_no）
     * @param itemName  项目名称快照
     * @param amountFen 金额（分）
     * @param status    费用状态（PENDING/SETTLED/CANCELLED）
     * @param settleId  结算单 id（SETTLED 行回填，其余 null）
     */
    private void insertFee(long id, String feeNo, String itemName, long amountFen, String status, Long settleId) {
        jdbcTemplate.update(
                "INSERT INTO billing.fee_record (id, fee_no, patient_id, visit_id, visit_type, charge_item_id,"
                        + " item_name_snapshot, unit_price_snapshot, quantity, amount, fee_category_snapshot,"
                        + " charge_source, source_ref, trigger_point, billing_date, billing_key, price_version,"
                        + " status, settlement_id, charged_at)"
                        + " VALUES (?, ?, 7001, ?, 'OUT', 1, ?, ?, 1, ?, '检查类', 'MANUAL', ?, 'MANUAL',"
                        + " (now() AT TIME ZONE 'Asia/Shanghai')::date, ?, 1, ?, ?, now() - interval '30 minutes')",
                id,
                feeNo,
                visitId(1),
                itemName,
                amountFen,
                amountFen,
                feeNo,
                feeNo + "|billing-key",
                status,
                settleId);
    }

    /**
     * 步骤②：overview 真值断言——六格逐字段+14 日趋势零填充+候诊表降序，零伪数据逐项指源。
     */
    @Test
    @Order(2)
    @DisplayName("②overview 真值：六格/14 日趋势/候诊表逐项与种子行一致")
    void overviewAggregatesRealSeededData() {
        JsonNode overview = exchangeJson("/api/v1/ops/workbench/overview");
        JsonNode metrics = overview.path("metrics");
        // 六格：今日门诊 3（含急诊）/候诊 3/今日收入 26000 分（SETTLED+PENDING，CANCELLED 不入）/
        // 在院 2（逻辑删行不入）/待配药 2（CREATED+PICKING，ISSUED 不入）/待结算 2（PENDING 未结算）
        assertThat(metrics.path("todayVisits").asLong()).isEqualTo(3L);
        assertThat(metrics.path("waitingCount").asLong()).isEqualTo(3L);
        assertThat(metrics.path("todayIncomeFen").asText()).isEqualTo("26000");
        assertThat(metrics.path("inHospitalCount").asLong()).isEqualTo(2L);
        assertThat(metrics.path("pendingDispenseCount").asLong()).isEqualTo(2L);
        assertThat(metrics.path("pendingSettleCount").asLong()).isEqualTo(2L);
        // 14 日趋势：点数=14、末点=今日 3 人（含 1 急诊）、倒点=昨日 2 人、其余日零填充
        JsonNode trend = overview.path("trend");
        assertThat(trend).hasSize(14);
        assertThat(trend.get(13).path("visitCount").asLong()).isEqualTo(3L);
        assertThat(trend.get(13).path("emergencyCount").asLong()).isEqualTo(1L);
        assertThat(trend.get(12).path("visitCount").asLong()).isEqualTo(2L);
        assertThat(trend.get(11).path("visitCount").asLong()).isZero();
        assertThat(trend.get(0).path("statDate").asText())
                .isEqualTo(LocalDate.now(TimeConstantsHolder.TZ).minusDays(13).toString());
        assertThat(trend.get(13).path("statDate").asText())
                .isEqualTo(LocalDate.now(TimeConstantsHolder.TZ).toString());
        // 候诊表：D01(2 人) 在前 D02(1 人) 在后（候诊人数降序），CANCELLED 干扰行不入面
        JsonNode waitingTable = overview.path("waitingTable");
        assertThat(waitingTable).hasSize(2);
        assertThat(waitingTable.get(0).path("deptCode").asText()).isEqualTo("D01");
        assertThat(waitingTable.get(0).path("waitingCount").asLong()).isEqualTo(2L);
        // 最长等待分钟≥89（最早行 90 分钟前播种，容忍取整抖动）
        assertThat(waitingTable.get(0).path("longestWaitingMinutes").asLong()).isGreaterThanOrEqualTo(89L);
        assertThat(waitingTable.get(1).path("deptCode").asText()).isEqualTo("D02");
        assertThat(waitingTable.get(1).path("waitingCount").asLong()).isEqualTo(1L);
        assertThat(overview.path("generatedAt").isMissingNode()).isFalse();
    }

    /**
     * 步骤③：缓存回写与命中——Redis 键在位 TTL≤5s（禁无 TTL 键红线），二次 GET generatedAt
     * 不变（读路径不触库、直算不重跑）。
     */
    @Test
    @Order(3)
    @DisplayName("③缓存 read-through：键在位 TTL 5s 上界+二次 GET 命中同快照")
    void overviewCachesWithTtl5sAndHitsOnSecondRead() {
        JsonNode first = exchangeJson("/api/v1/ops/workbench/overview");
        // 缓存键在位且 TTL 落 (0, 5]（A.5-1 禁无 TTL 键：-1/-2 形态均违规）
        assertThat(redisTemplate.hasKey(SNAPSHOT_KEY)).isTrue();
        Long ttl = redisTemplate.getExpire(SNAPSHOT_KEY);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isPositive();
        assertThat(ttl).isLessThanOrEqualTo(5L);
        // 命中：二次读取 generatedAt 与首次一致（若直算重跑则时点必推进）
        JsonNode second = exchangeJson("/api/v1/ops/workbench/overview");
        assertThat(second.path("generatedAt").asText())
                .isEqualTo(first.path("generatedAt").asText());
    }

    /**
     * 步骤④：events 五源断言——三 STOMP 主题指引 + billing 待支付/pharmacy 待配药双源 4 事件
     * + 危急值恒空段带降级标志。时点设计：待配药 PICKING 行最近（-10 分钟）居首，两待支付行
     * （-30 分钟）殿后；同刻行不断言相对序（库端时戳微秒差非契约面）。
     */
    @Test
    @Order(4)
    @DisplayName("④events 五源：三主题指引+双源 4 事件+危急值空段降级标志")
    void eventsAssemblesFiveSourcesWithDegradedCriticalValues() {
        JsonNode events = exchangeJson("/api/v1/ops/workbench/events");
        // 主题指引：三既有端点各一条（/ws/iot、/ws/nursing、/ws/outpatient——复用不新建）
        JsonNode topics = events.path("topics");
        assertThat(topics).hasSize(3);
        assertThat(topics.get(0).path("endpoint").asText()).isEqualTo("/ws/iot");
        assertThat(topics.get(0).path("topic").asText()).isEqualTo("/topic/iot/device-status/{wardId}");
        assertThat(topics.get(1).path("endpoint").asText()).isEqualTo("/ws/nursing");
        assertThat(topics.get(2).path("endpoint").asText()).isEqualTo("/ws/outpatient");
        // 双源合并：2 待支付（PENDING）+2 待配药（CREATED+PICKING）=4 事件
        JsonNode eventRows = events.path("events");
        assertThat(eventRows).hasSize(4);
        // 首行=最近时点的待配药 PICKING 行（-10 分钟，非计费类零金额）
        assertThat(eventRows.get(0).path("type").asText()).isEqualTo("DISPENSE_PENDING");
        assertThat(eventRows.get(0).path("source").asText()).isEqualTo("pharmacy");
        assertThat(eventRows.get(0).path("amountFen").isNull()).isTrue();
        // 源构成：恰好 2 待支付（金额字符串化出网）+2 待配药（零金额）
        long feePendingCount = 0;
        long dispensePendingCount = 0;
        for (JsonNode row : eventRows) {
            if ("FEE_PENDING".equals(row.path("type").asText())) {
                feePendingCount++;
                assertThat(row.path("source").asText()).isEqualTo("billing");
                assertThat(row.path("amountFen").asText()).isIn("3500", "12500");
            } else if ("DISPENSE_PENDING".equals(row.path("type").asText())) {
                dispensePendingCount++;
            }
        }
        assertThat(feePendingCount).isEqualTo(2L);
        assertThat(dispensePendingCount).isEqualTo(2L);
        // 危急值缺位降级：恒空数组+判别标志（M07 未建——前端降级文案渲染面）
        assertThat(events.path("criticalValues")).isEmpty();
        assertThat(events.path("criticalValueDegraded").asBoolean()).isTrue();
    }

    /**
     * 步骤⑤：未登录 401 拒绝（端点在鉴权拦截面内，非免认证白名单）。
     */
    @Test
    @Order(5)
    @DisplayName("⑤鉴权面：未登录 GET overview 401（非免认证白名单端点）")
    void overviewRequiresAuthentication() {
        ResponseEntity<String> resp = restTemplate.getForEntity("/api/v1/ops/workbench/overview", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    /** 种子主键派生（基座+序）。 */
    private static long seedId(int seq) {
        return SEED_ID_BASE + seq;
    }

    /** 种子就诊号（O 型 14 位：O+yyyyMMdd+5 位流水，今日流水=序号；候诊/费用行引用同日就诊）。 */
    private static String visitId(int seq) {
        String datePart = LocalDate.now(TimeConstantsHolder.TZ).toString().replace("-", "");
        return "O" + datePart + String.format("%05d", seq);
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

    /** 测试用北京时区持有（与 TimeConstants.HEALTHCARE_TZ 同值断言口径）。 */
    static final class TimeConstantsHolder {
        static final java.time.ZoneId TZ = com.fuyun.common.constants.TimeConstants.HEALTHCARE_TZ;

        private TimeConstantsHolder() {}
    }
}
