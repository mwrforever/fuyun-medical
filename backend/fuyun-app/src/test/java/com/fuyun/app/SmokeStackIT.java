package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * 基础设施装配冒烟集成测试（CF-1 最小验证）。
 *
 * <p>业务意图：以与 deploy compose 严格一致的镜像 tag（backend 宪法 C.5-4）拉起 TimescaleDB / Redis / RabbitMQ
 * 三容器，经 Boot 原生 {@code @ServiceConnection} 注入连接细节，test profile 启动 fuyun-app 完整上下文，
 * 依序验证三条最小基础链路：Flyway 首跑建表 → Redis 读写一回合 → RabbitMQ 队列声明与一帧收发。
 * 上下文成功启动本身即证明数据源 / Redis / RabbitMQ 三连接与 Flyway migrate 全部无异常。
 *
 * <p>容器声明为 static 类级共享（同类全部用例复用一套容器，backend 宪法 C.5-4）；不启用 reuse（实验特性，破坏隔离）。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SmokeStackIT {

    /** TimescaleDB 容器：Flyway 迁移目标库；tag 与 deploy compose 严格一致，兼容性声明对齐 postgres 基底 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：测试容器不设密码，Boot 按镜像名自动识别 redis 连接细节 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：挂载与 compose rabbitmq.conf 同语义的服务端默认队列类型配置，无显式类型的声明队列即落 quorum */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-smoke.conf");

    /** 测试资产假密钥（64 字符，仅具 IT 意义，与任何真实凭证无关；真实密钥只经环境变量注入） */
    private static final String TEST_HMAC_SECRET = "it-only-fake-hmac-secret-0123456789abcdef0123456789abcdef";

    /**
     * 注入测试用 HMAC 密钥：B3.2 认证链路装配后上下文含 SecurityProperties（fuyun.security.*），
     * 密钥缺失即启动 fail-fast；既有 IT 以假密钥维持基础设施冒烟语义（BRIEF-PR3-01 §5 同款姿态）。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_HMAC_SECRET);
    }

    /** 依赖经测试构造器注入（@Autowired 显式声明可注入构造器，规避字段注入，backend 宪法 A.1-7） */
    private final JdbcTemplate jdbcTemplate;

    private final StringRedisTemplate stringRedisTemplate;

    private final AmqpAdmin amqpAdmin;

    private final RabbitTemplate rabbitTemplate;

    /**
     * 构造器注入：Spring 6.2 测试构造器默认按注解识别（annotated 模式），须显式标注 @Autowired
     * 方可让 SpringExtension 从上下文解析各依赖；非空，来源为 fuyun-app test 上下文自动装配。
     *
     * @param jdbcTemplate JDBC 模板，用于 Flyway 迁移历史表断言
     * @param stringRedisTemplate String 序列化 Redis 模板，用于读写一回合探针
     * @param amqpAdmin 自动装配的 RabbitAdmin，用于声明冒烟队列
     * @param rabbitTemplate RabbitTemplate，用于一帧收发探针
     */
    @Autowired
    SmokeStackIT(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate stringRedisTemplate,
            AmqpAdmin amqpAdmin,
            RabbitTemplate rabbitTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Flyway 链路验证：迁移历史表必须落在公共 schema（PG 默认 public）。
     *
     * <p>骨架期无任何迁移文件，首跑仅创建 flyway_schema_history 且结果集为空属预期；
     * 查询不抛异常即证明表存在、结构可查、落点正确，逐行非空断言在首个迁移落地后自然生效。
     */
    @Test
    @DisplayName("Flyway 首跑：flyway_schema_history 落在公共 schema public 且可查")
    void flywayHistoryTableLandsInPublicSchema() {
        // information_schema 精确命中一次，证明历史表存在于 public schema 而非其他落点
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'",
                Integer.class);
        assertThat(tableCount).isEqualTo(1);
        // installed_rank 记录可查：查询本身不抛异常即结构验证通过
        List<Map<String, Object>> historyRows = jdbcTemplate.queryForList(
                "SELECT installed_rank, version, description, success FROM public.flyway_schema_history");
        assertThat(historyRows)
                .allSatisfy(row -> assertThat(row.get("installed_rank")).isNotNull());
    }

    /**
     * Redis 链路验证：String 读写一回合，值一致、TTL 为正、删除生效。
     *
     * <p>键名遵循 fy:{module}:{biz}:{id} 冒号分层（backend 宪法 A.5-1）；
     * TTL 60s 对齐「除白名单外禁无过期键」约束。
     */
    @Test
    @DisplayName("Redis 读写一回合：值一致、TTL 为正、删除生效")
    void redisReadWriteWithTtlRoundTrip() {
        // 探针键按命名规范分层，uuid 保证用例间互不干扰
        String key = "fy:app:it-smoke:" + UUID.randomUUID();
        stringRedisTemplate.opsForValue().set(key, "fy-smoke", Duration.ofSeconds(60));
        assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("fy-smoke");
        // 过期键红线：TTL 必须为正（-1 无过期 / -2 键不存在均视为违规形态）
        assertThat(stringRedisTemplate.getExpire(key)).isPositive();
        // 清理探针键防残留，并断言删除生效
        stringRedisTemplate.delete(key);
        assertThat(stringRedisTemplate.hasKey(key)).isFalse();
    }

    /**
     * RabbitMQ 链路验证：队列声明 + 一帧收发。
     *
     * <p>队列声明不带显式类型，实际类型由服务端 default_queue_type=quorum 决定（与 compose 挂载的
     * rabbitmq.conf 同语义），对齐「业务队列全部 quorum 类型」约束（backend 宪法 A.5-4）；
     * 队列清理交给容器销毁，不在用例内做删除（容器级隔离保证不污染外部环境）。
     */
    @Test
    @DisplayName("RabbitMQ：声明队列 q.it.smoke 并完成一帧收发")
    void rabbitQueueDeclareAndSendReceiveOneFrame() {
        amqpAdmin.declareQueue(new Queue("q.it.smoke"));
        // 走默认交换机（routing key = 队列名）发送一帧字符串
        rabbitTemplate.convertAndSend("q.it.smoke", "fy-smoke-frame");
        // 5s 超时内取回并断言内容一致，证明声明-发送-消费整条最小链路可用（当前 Spring AMQP 3.2.x 超时参数为毫秒）
        assertThat(rabbitTemplate.receiveAndConvert("q.it.smoke", 5000L)).isEqualTo("fy-smoke-frame");
    }
}
