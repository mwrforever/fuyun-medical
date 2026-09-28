package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * 非数值遥测承载验收锚点 IT（TASK.md W-7 闭合锚，P2 PR-2 Task 18）：V1005 raw_value 列 + D-9
 * 裁决承载语义的四形态实链断言——非数值标量原文落行、对象/数组紧凑 JSON 承载、混合批数值行
 * 不回归、V1006 重插演示夹具的绑定快照富化正常。
 *
 * <p>业务意图：W-7 改造后「整批全量入库不丢行」是数据面红线（value 仅数值定型行填写、非数值
 * value=NULL + raw_value 原文 + quality 强制 BAD），此前由单测与管道 IT 步骤⑨间接覆盖，本类以
 * HTTP 兜底通道（与 AMQP 同一 ingest 服务实现）直打四形态并逐行库端回读断言：
 * ①标量非数值（"N/A"）原文承载；②对象文本（"{...}"）与③数组文本经 Jackson 树规整为紧凑 JSON
 * 标准输出（无空格）承载——规整失败按标量原文兜底的分支由形似 JSON 但非法的文本（"{bad}"）覆盖；
 * ④混合批数值行 NUMERIC 定型 + raw_value NULL + 时刻新鲜 GOOD（不回归守卫）；⑤夹具设备
 * fuyun-demo-001（V1006 重插 BOUND 行）遥测落库携带 patient_id=1 / visit_id='I2026090100001'
 * 字符串快照——W-7 承载改造与 W-10 类型改造的交汇面（14 位字符串经数值列隔离不受污染）。
 *
 * <p>容器三件套与 {@link IotTelemetryPipelineIT} 完全同款（类级独占 + @ServiceConnection）；
 * 登录/POST 助手复用 {@link FuyunStackITBase}。本类不启用 AMQP 消费，纯 HTTP 兜底同步入库
 * （202 返回即落库完成）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotNonNumericIngestIT extends FuyunStackITBase {

    /** TimescaleDB 容器：iot_telemetry 承载形态断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：上下文完整装配所需（告警引擎快照/幂等构件），本类不直接断言 */
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
    private static final String TEST_FALLBACK_TOKEN = "it-iot-nonnum-fallback-token";

    /** 四形态承载断言的设备号（词表外指标直通行，不触发任何种子规则） */
    private static final String DEVICE_ID = "it-nn-001";

    /** V1006 重插演示夹具绑定所属设备号（富化断言载体） */
    private static final String FIXTURE_DEVICE_ID = "fuyun-demo-001";

    /** 夹具绑定快照断言值（V1006 步骤③ 字面值） */
    private static final long FIXTURE_PATIENT_ID = 1L;

    private static final String FIXTURE_VISIT_ID = "I2026090100001";

    /** 固定历史发生时刻：非数值行承载断言锚（quality 恒 BAD，时间偏差不构成干扰变量） */
    private static final String PAST_OCCURRED_AT = "2026-09-11T01:02:03Z";

    /**
     * 注入兜底通道共享密钥（ingest 直打 HTTP 兜底真实鉴权面；application.yml 该键空默认值
     * fail-closed，未注入则全数 401。FuyunStackITBase 密钥三元组的 @DynamicPropertySource
     * 对本类继续生效——基类/子类动态属性方法叠加处理）。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerFallbackProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.iot.fallback.token", () -> TEST_FALLBACK_TOKEN);
    }

    /**
     * 步骤①：非数值标量经 ingest 落行——value='N/A' → iot_telemetry 行 value=NULL、
     * raw_value='N/A' 原文、quality=BAD（非数值定型标注不阻断入库），兜底通道 202 同步完成。
     */
    @Test
    @Order(1)
    @DisplayName("非数值标量：value NULL + raw_value 原文 + quality BAD（D-9 承载红线）")
    void ingestsNonNumericScalarIntoRawValue() {
        acceptFallback(ingestBody(DEVICE_ID, "vital.nn-scalar", "N/A", PAST_OCCURRED_AT));

        Map<String, Object> row = queryRow(DEVICE_ID, "vital.nn-scalar");
        assertThat(row.get("value")).as("非数值行 value 落 NULL（哨兵值禁回填）").isNull();
        assertThat(row.get("raw_value")).as("标量原文承载").isEqualTo("N/A");
        assertThat(row.get("quality")).as("非数值定型标注 BAD 不阻断入库").isEqualTo("BAD");
    }

    /**
     * 步骤②：对象/数组文本行紧凑 JSON 承载——形似 JSON 的对象与数组原文经 Jackson 树规整为
     * 标准紧凑输出（键序保留、无空白）落 raw_value；形似 JSON 但非法的文本按标量原文兜底承载
     * （规整失败不丢行）。
     */
    @Test
    @Order(2)
    @DisplayName("对象/数组：紧凑 JSON 标准输出承载；非法 JSON 按标量原文兜底不丢行")
    void ingestsObjectAndArrayAsCompactJson() {
        acceptFallback(ingestBody(
                DEVICE_ID, "vital.nn-object", "{ \"rate\" : 1, \"tags\" : [ \"a\", 2 ] }", PAST_OCCURRED_AT));
        acceptFallback(ingestBody(DEVICE_ID, "vital.nn-array", "[ 1,  2, \"x\" ]", PAST_OCCURRED_AT));
        // 形似 JSON（大括号包裹）但树读必败——命中 toRawValueText 的规整失败原文兜底分支
        acceptFallback(ingestBody(DEVICE_ID, "vital.nn-broken", "{bad}", PAST_OCCURRED_AT));

        assertThat(queryRow(DEVICE_ID, "vital.nn-object").get("raw_value"))
                .as("对象文本规整为紧凑 JSON（无空格标准输出）")
                .isEqualTo("{\"rate\":1,\"tags\":[\"a\",2]}");
        assertThat(queryRow(DEVICE_ID, "vital.nn-array").get("raw_value"))
                .as("数组文本规整为紧凑 JSON")
                .isEqualTo("[1,2,\"x\"]");
        assertThat(queryRow(DEVICE_ID, "vital.nn-broken").get("raw_value"))
                .as("非法 JSON 按标量原文兜底承载（不因规整失败丢行）")
                .isEqualTo("{bad}");
    }

    /**
     * 步骤③：混合批数值行不回归——与步骤①②同链注入数值 '72.5'（时刻新鲜偏差 <300s）→
     * value NUMERIC 定型 72.5、raw_value NULL、quality GOOD（W-7 分流对数值零影响）。
     */
    @Test
    @Order(3)
    @DisplayName("混合批数值不回归：value=72.5 NUMERIC、raw_value NULL、quality GOOD")
    void numericRowsUnaffectedByNonNumericSupport() {
        String freshOccurredAt = Instant.now()
                .minus(30, ChronoUnit.SECONDS)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();
        acceptFallback(ingestBody(DEVICE_ID, "vital.nn-numeric", "72.5", freshOccurredAt));

        Map<String, Object> row = queryRow(DEVICE_ID, "vital.nn-numeric");
        assertThat((BigDecimal) row.get("value"))
                .as("数值行 NUMERIC 定型（scale 保留）")
                .isEqualByComparingTo(new BigDecimal("72.5"));
        assertThat(row.get("raw_value")).as("数值行 raw_value 恒 NULL").isNull();
        assertThat(row.get("quality")).as("新鲜时刻数值行 GOOD（时间合理性步不干扰承载断言）").isEqualTo("GOOD");
    }

    /**
     * 步骤④：V1006 重插夹具富化正常——演示设备 fuyun-demo-001（BOUND 夹具 patient_id=1、
     * visit_id='I2026090100001'）遥测落库携带绑定快照两列，visit_id 为 14 位字符串原样承载
     * （W-7 承载与 W-10 类型改造交汇面）；无绑定设备落行两列 NULL 仍入库（未关联仍入库口径）。
     */
    @Test
    @Order(4)
    @DisplayName("夹具富化：V1006 重插 BOUND 行快照冗余（patient 1 / CF-3 字符串），未关联行 NULL 入库")
    void enrichesTelemetryWithReinsertedFixtureBinding() {
        String freshOccurredAt = Instant.now()
                .minus(20, ChronoUnit.SECONDS)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();
        acceptFallback(ingestBody(FIXTURE_DEVICE_ID, "vital.nn-fixture", "88", freshOccurredAt));
        acceptFallback(ingestBody(DEVICE_ID, "vital.nn-unbound", "99", freshOccurredAt));

        Map<String, Object> enriched = queryRow(FIXTURE_DEVICE_ID, "vital.nn-fixture");
        assertThat(((Number) enriched.get("patient_id")).longValue())
                .as("夹具 BOUND 行快照冗余患者")
                .isEqualTo(FIXTURE_PATIENT_ID);
        assertThat(enriched.get("visit_id"))
                .as("CF-3 14 位字符串快照原样承载（V1006 重插夹具联动）")
                .isEqualTo(FIXTURE_VISIT_ID);
        assertThat(((String) enriched.get("visit_id")).length()).isEqualTo(14);

        Map<String, Object> unbound = queryRow(DEVICE_ID, "vital.nn-unbound");
        assertThat(unbound.get("patient_id")).as("无绑定设备患者列 NULL").isNull();
        assertThat(unbound.get("visit_id")).as("无绑定设备就诊列 NULL 仍入库").isNull();
        assertThat((BigDecimal) unbound.get("value")).isEqualByComparingTo(new BigDecimal("99"));
    }

    // ---------------------------------------------------------------- 兜底通道与回读助手

    /** 兜底通道受理断言（202 + accepted true；HTTP 线程同步完成 ingest 事务）。 */
    private void acceptFallback(ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Iot-Fallback-Token", TEST_FALLBACK_TOKEN);
        ResponseEntity<String> resp =
                restTemplate.postForEntity("/ingest/iotda-fallback", new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().value())
                .as("兜底受理应 202，实况：%s", resp.getBody())
                .isEqualTo(202);
    }

    /** 构造兜底请求体（unit/quality 走契约缺省）。 */
    private ObjectNode ingestBody(String deviceId, String metricCode, String value, String occurredAt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("deviceId", deviceId)
                .put("metricCode", metricCode)
                .put("value", value)
                .put("occurredAt", occurredAt);
        return body;
    }

    /** 设备+指标维度单行回读（各步骤指标唯一，行必存在）。 */
    private Map<String, Object> queryRow(String deviceId, String metricCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT value, raw_value, quality, patient_id, visit_id FROM iot.iot_telemetry"
                        + " WHERE device_id = ? AND metric_code = ?",
                deviceId,
                metricCode);
        assertThat(rows)
                .as("遥测行存在（device=" + deviceId + "，metric=" + metricCode + "）")
                .hasSize(1);
        return rows.get(0);
    }
}
