package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
 * IoT 迁移与策略作业断言集成测试（PR-4 B4.1 交付，TASK.md T-R3-2 实测证据的持久化落点）。
 *
 * <p>业务意图：上下文启动即 Flyway 全量重放 V1–V403（启动成功 = 迁移链重放通过），在此之上对 iot
 * schema 的五类交付物逐一断言：① iot_telemetry 遥测超表真实存在且已开启列存压缩；② 压缩/保留策略
 * 后台作业各一（proc_name 断言值取 2.29.2 实测视图输出——add_columnstore_policy 落盘的作业名为
 * policy_compression、add_retention_policy 落 policy_retention，防止"本机实测后人走茶凉"）；③
 * uk_iot_telemetry_device_metric_time 唯一索引定义同时含 device_id/metric_code/occurred_at 三列
 * （分区列必须入唯一键的 TimescaleDB 硬约束红线，该索引即遥测写入幂等载体）；④ V400–V401 三张
 * 业务表与绑定部分唯一索引存在；⑤ integration.event_registry 含 iot.device.status-changed 种子
 * 登记行（先登记后订阅治理链路的种子回归）。
 *
 * <p>容器三件套与 {@link SmokeStackIT} 完全同款（tag 与 deploy compose 严格一致 + it/rabbitmq.conf
 * 挂载 + static 类级共享 + @ServiceConnection），本类独立声明容器不改既有 IT。
 *
 * <p>五步断言按简报 §2 编号顺序执行（@Order 表达规格序，断言彼此独立、无跨步累积状态）。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotMigrationIT {

    /** TimescaleDB 容器：Flyway 迁移目标库与超表/策略作业断言来源；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：上下文完整装配所需（幂等构件等），本类不直接断言 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：上下文完整装配所需（消息治理三交换机），本类不直接断言 */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假密钥（64 字符，仅具 IT 意义，与任何真实凭证无关；真实密钥只经环境变量注入） */
    private static final String TEST_HMAC_SECRET = "it-only-fake-hmac-secret-0123456789abcdef0123456789abcdef";

    /**
     * 注入测试用 HMAC 密钥：认证链路装配后上下文含 SecurityProperties（fuyun.security.*），密钥缺失
     * 即启动 fail-fast；既有 IT 以假密钥维持迁移断言语义（BRIEF-PR3-01 §5 同款姿态）。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_HMAC_SECRET);
    }

    /** 依赖经测试构造器注入（@Autowired 显式声明可注入构造器，规避字段注入，backend 宪法 A.1-7） */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入：Spring 6.2 测试构造器默认按注解识别（annotated 模式），须显式标注 @Autowired
     * 方可让 SpringExtension 从上下文解析各依赖；非空，来源为 fuyun-app test 上下文自动装配。
     *
     * @param jdbcTemplate JDBC 模板，用于迁移产物（超表/作业/索引/种子行）的业务断言
     */
    @Autowired
    IotMigrationIT(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 断言①：iot_telemetry 已建为 TimescaleDB 超表且列存压缩已开启。
     *
     * <p>compression_enabled=true 证明 V402 的 compress 段（开关 + segmentby/orderby）真实生效，
     * 后续压缩策略作业才有作用对象。
     */
    @Test
    @Order(1)
    @DisplayName("遥测超表断言：iot.iot_telemetry 落位 timescaledb_information.hypertables 且压缩已开启")
    void telemetryTableIsHypertableWithCompressionEnabled() {
        List<Map<String, Object>> hypertables = jdbcTemplate.queryForList(
                "SELECT hypertable_schema, hypertable_name, num_dimensions, compression_enabled"
                        + " FROM timescaledb_information.hypertables"
                        + " WHERE hypertable_schema = 'iot' AND hypertable_name = 'iot_telemetry'");
        assertThat(hypertables)
                .as("iot.iot_telemetry 必须已注册为超表（V402 create_hypertable 按天分区）")
                .hasSize(1);
        assertThat(hypertables.get(0).get("compression_enabled"))
                .as("超表列存压缩必须已开启")
                .isEqualTo(true);
    }

    /**
     * 断言②：压缩与保留策略后台作业各一且均处于调度状态。
     *
     * <p>proc_name 断言值为 T-R3-2 在 2.29.2 实测视图输出：add_columnstore_policy（CALL 过程）落
     * policy_compression 作业、add_retention_policy（SELECT 函数）落 policy_retention 作业；按超表
     * 过滤，排除实例级 policy_job_stat_history_retention 等无关作业行。
     */
    @Test
    @Order(2)
    @DisplayName("策略作业断言：压缩/保留策略作业各一挂靠 iot_telemetry 且均为调度状态")
    void compressionAndRetentionPolicyJobsAreScheduled() {
        List<Map<String, Object>> jobs =
                jdbcTemplate.queryForList("SELECT proc_name, scheduled FROM timescaledb_information.jobs"
                        + " WHERE hypertable_schema = 'iot' AND hypertable_name = 'iot_telemetry'"
                        + " AND proc_name IN ('policy_compression', 'policy_retention') ORDER BY proc_name");
        assertThat(jobs).as("V402 必须为遥测超表登记压缩（7 天）与保留（90 天）两个策略作业").hasSize(2);
        assertThat(jobs)
                .extracting(row -> row.get("proc_name"))
                .containsExactly("policy_compression", "policy_retention");
        assertThat(jobs).allSatisfy(row -> assertThat(row.get("scheduled")).isEqualTo(true));
    }

    /**
     * 断言③：遥测唯一索引定义含三列且为 UNIQUE——分区列 occurred_at 必须在唯一键内。
     *
     * <p>TimescaleDB 硬约束：唯一约束必须包含分区列；该索引同时是遥测写入幂等载体（批量写 ON
     * CONFLICT 冲突忽略的键），索引缺失或列不全即写入幂等失效。
     */
    @Test
    @Order(3)
    @DisplayName("唯一索引断言：uk_iot_telemetry_device_metric_time 为 UNIQUE 且含分区列 occurred_at 在内三列")
    void telemetryUniqueIndexCoversPartitionColumn() {
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'iot' AND indexname = ?",
                String.class,
                "uk_iot_telemetry_device_metric_time");
        assertThat(indexDef).as("遥测唯一索引必须存在").isNotBlank();
        assertThat(indexDef).as("必须为唯一索引（写入幂等载体）").contains("UNIQUE INDEX");
        assertThat(indexDef).as("唯一键必须含设备号").contains("device_id");
        assertThat(indexDef).as("唯一键必须含指标编码").contains("metric_code");
        assertThat(indexDef).as("唯一键必须含分区列 occurred_at（TimescaleDB 硬约束红线）").contains("occurred_at");
    }

    /**
     * 断言④：V400–V401 三张业务表与绑定部分唯一索引存在。
     *
     * <p>部分唯一索引 uk_iot_binding_device_bound 承载"同一设备同一时刻至多一条绑定中"业务约束，
     * 其定义必须含 status='BOUND' 与 deleted=0 条件（逻辑删行不占用唯一性）。
     */
    @Test
    @Order(4)
    @DisplayName("业务表断言：设备档案/绑定/消费错误日志三表落位，绑定部分唯一索引含 BOUND 与 deleted 条件")
    void deviceBindingAndConsumeErrorLogTablesExist() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'iot'"
                        + " AND table_name IN ('iot_device', 'iot_binding', 'iot_consume_error_log')",
                Integer.class);
        assertThat(tableCount).as("V400–V401 三张业务表必须全部落位").isEqualTo(3);

        String bindingIndexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'iot' AND indexname = ?",
                String.class,
                "uk_iot_binding_device_bound");
        assertThat(bindingIndexDef).as("绑定部分唯一索引必须存在").isNotBlank();
        assertThat(bindingIndexDef).as("必须为唯一索引").contains("UNIQUE INDEX");
        assertThat(bindingIndexDef).as("必须限定绑定中状态（同设备至多一条绑定中）").contains("BOUND");
        assertThat(bindingIndexDef).as("必须限定未逻辑删（deleted = 0）").contains("deleted");
    }

    /**
     * 断言⑤：事件契约台账含 iot.device.status-changed 种子登记行。
     *
     * <p>先登记后订阅治理链路的种子回归（V403 幂等种子）：producer=iot、status=ACTIVE、订阅清单含
     * iot（B4.3 起本模块自事件订阅上线——IotMessagingConfig 消费队列声明构件在建队列时自动补登记；
     * B4.1 交付时的"零订阅"断言因本订阅落地同步失效改造）。
     */
    @Test
    @Order(5)
    @DisplayName("事件种子断言：event_registry 含 iot.device.status-changed 行且 producer=iot、ACTIVE、订阅含 iot")
    void deviceStatusChangedEventIsRegistered() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT producer_module, subscriber_modules, status FROM integration.event_registry"
                        + " WHERE event_type = 'iot.device.status-changed'");
        assertThat(rows).as("iot.device.status-changed 必须已登记（V403，先登记后订阅）").hasSize(1);
        assertThat(rows.get(0).get("producer_module")).as("生产方必须为 iot 模块").isEqualTo("iot");
        assertThat(rows.get(0).get("status")).as("登记状态必须为 ACTIVE").isEqualTo("ACTIVE");
        assertThat(rows.get(0).get("subscriber_modules"))
                .asString()
                .as("自事件订阅已落地（治理队列声明自动补登记）")
                .contains("iot");
    }
}
