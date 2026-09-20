package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuyun.outpatient.mapper.QueueTicketMapper;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
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
 * 门诊候诊队列 schema 探针 IT（Task 7 fix round 1 审查官裁定「真库锁一条验证」的持久化落点；
 * 单测 mock 无法触发数据库约束，故以真库回放锁定两条修复语义）：
 *
 * <p>① {@code uk_ticket_visit} 唯一索引形态=（visit_id, queue_id, queue_seq）三列（Important-1），
 * 且行为锁定：同就诊跨队列转接的新队列新票（seq 按队列独立从 1 起签发）INSERT 共存不冲突——
 * 原 (visit_id, queue_seq) 两列键下该场景命中 DuplicateKey 500（DEP001 报到→转当日尚未发号的
 * DEP002 主流路径）；同队列同序重复建票仍被拒（防线不因扩键而松动）。
 *
 * <p>② {@code selectWaiting} 惰性重建数据源词表覆盖 WAITING 候诊+PASSED 过号再入、排除 CALLED
 * （Important-2 裁决①：pass 降级重入票在 Redis 重启后不得静默跌出队列）——mapper 真库执行锁定。
 *
 * <p>容器三件套与 {@link IotMigrationIT} 完全同款（tag 与 deploy compose 严格一致+it/rabbitmq.conf
 * 挂载+static 类级共享+@ServiceConnection）；上下文启动即 Flyway 全量重放（V202 在位为前置）。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutpatientQueueSchemaIT {

    /** TimescaleDB 容器：Flyway 迁移目标库与队列约束探针来源；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：上下文完整装配所需（队列 ZSET 构件等），本类不直接断言 */
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
     * 注入测试用 HMAC 密钥：认证链路装配后上下文含 SecurityProperties（fuyun.security.*），密钥
     * 缺失即启动 fail-fast（IotMigrationIT 同款姿态）。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_HMAC_SECRET);
    }

    private final JdbcTemplate jdbcTemplate;

    private final QueueTicketMapper queueTicketMapper;

    /**
     * 构造器注入（Spring 6.2 测试构造器默认按注解识别，须显式 @Autowired 方可让 SpringExtension
     * 从上下文解析依赖，backend 宪法 A.1-7）。
     *
     * @param jdbcTemplate      JDBC 模板，非空；约束探针写入与索引断言
     * @param queueTicketMapper 票据 mapper，非空；selectWaiting 词表语义真库执行锁定
     */
    @Autowired
    OutpatientQueueSchemaIT(JdbcTemplate jdbcTemplate, QueueTicketMapper queueTicketMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.queueTicketMapper = queueTicketMapper;
    }

    @Test
    @Order(1)
    @DisplayName("uk_ticket_visit 索引形态：三列键（visit_id, queue_id, queue_seq）——fix round 1 Important-1")
    void ukTicketVisitIndexCoversQueueColumn() {
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uk_ticket_visit'", String.class);

        assertThat(indexDef).isNotBlank();
        assertThat(indexDef).contains("visit_id").contains("queue_id").contains("queue_seq");
    }

    @Test
    @Order(2)
    @DisplayName("跨队列转接共存：同就诊 DEP001/DEP002 各建 seq=1 票均成功（旧两列键此处 DuplicateKey 500）；同队列同序仍拒")
    void transferTicketCoexistsAcrossQueuesAndSameQueueDuplicateRejected() {
        String insert = "INSERT INTO outpatient.queue_ticket (id, visit_id, queue_id, ticket_no, ticket_type, "
                + "priority_score, queue_seq, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'WAITING')";
        // 原队列首票（DEP001 报到建票 seq=1）
        jdbcTemplate.update(insert, 900101L, "O2026092100001", "DEP001", "A001", "FIRST", 100, 1);
        // 跨队列转接新票（DEP002 当日尚未发号 seq=1）——旧索引形态下本插入即冲突（修复行为锁定）
        jdbcTemplate.update(insert, 900102L, "O2026092100001", "DEP002", "A001", "FIRST", 100, 1);

        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outpatient.queue_ticket WHERE visit_id = 'O2026092100001'", Long.class);
        assertThat(count).isEqualTo(2L);

        // 同队列（DEP001）同序重复建票仍被 uk_ticket_visit 拒绝（防线不因 queue_id 入键而松动）
        assertThatThrownBy(
                        () -> jdbcTemplate.update(insert, 900103L, "O2026092100001", "DEP001", "A009", "FIRST", 100, 1))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @Order(3)
    @DisplayName("selectWaiting 重建词表：WAITING+PASSED 在列、CALLED 排除（fix round 1 Important-2 裁决①）")
    void selectWaitingCoversWaitingAndPassedOnly() {
        String insert = "INSERT INTO outpatient.queue_ticket (id, visit_id, queue_id, ticket_no, ticket_type, "
                + "priority_score, queue_seq, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(insert, 900201L, "O2026092100002", "DEP009", "A001", "FIRST", 100, 1, "WAITING");
        jdbcTemplate.update(insert, 900202L, "O2026092100002", "DEP009", "A002", "FIRST", 100, 2, "PASSED");
        jdbcTemplate.update(insert, 900203L, "O2026092100002", "DEP009", "A003", "FIRST", 100, 3, "CALLED");

        Set<String> statuses = queueTicketMapper.selectWaiting("DEP009").stream()
                .map(ticket -> ticket.getStatus().getCode())
                .collect(Collectors.toSet());

        assertThat(statuses).containsExactlyInAnyOrder("WAITING", "PASSED");
    }
}
