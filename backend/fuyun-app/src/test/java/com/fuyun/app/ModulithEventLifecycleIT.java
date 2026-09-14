package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Modulith 事件发布注册表生命周期 IT：验证「事务内暂存 → 提交后异步投递 → 监听器失败保持未完成 →
 * 编程式重投成功 → 过期清理」全链（D-2 裁决设计的运行时验收，宪法 B.3-3）。
 *
 * <p>容器三件套与 MessagingGovernanceIT 完全同款（tag 与 deploy compose 严格一致 + it/rabbitmq.conf
 * 挂载 + static 类级共享 + @ServiceConnection）；V6/V7 迁移由 Flyway 随上下文启动自动应用。
 * 不引入 awaitility（轮询 + 闩锁超时已覆盖等待语义，仓库既有口径）。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ModulithEventLifecycleIT {

    /** TimescaleDB 容器：event_publication/shedlock 断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：全量上下文装配所需（幂等构件真实存储） */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：全量上下文装配所需（治理构件队列声明） */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假密钥（仅具 IT 意义，与任何真实凭证无关；SecurityProperties fail-fast 要求） */
    private static final String TEST_HMAC_SECRET = "it-only-fake-hmac-secret-0123456789abcdef0123456789abcdef";

    @DynamicPropertySource
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_HMAC_SECRET);
    }

    /** 测试用领域事件 record：仅本 IT 意义，载荷为随机事件标识便于 serialized_event 定位 */
    record LifecycleProbeEvent(UUID eventId) {}

    /** 监听器状态机：FAIL 态下抛异常模拟首次投递失败（重投前切回 SUCCEED） */
    enum ProbeState {
        SUCCEED,
        FAIL
    }

    /** 重投成功闩锁：监听器首次成功执行时放行 */
    static final CountDownLatch SUCCEED_LATCH = new CountDownLatch(1);

    /** 测试监听器：@ApplicationModuleListener 语义（提交后异步 + 独立事务）的真实验证载体 */
    static class LifecycleProbeListener {

        static final AtomicReference<ProbeState> STATE = new AtomicReference<>(ProbeState.FAIL);

        @ApplicationModuleListener
        void on(LifecycleProbeEvent event) {
            if (STATE.get() == ProbeState.FAIL) {
                throw new IllegalStateException("首次投递按测试设计失败（重投前保持未完成态）");
            }
            SUCCEED_LATCH.countDown();
        }
    }

    @TestConfiguration
    static class ProbeConfiguration {

        @Bean
        LifecycleProbeListener lifecycleProbeListener() {
            return new LifecycleProbeListener();
        }
    }

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    IncompleteEventPublications incompletePublications;

    @Autowired
    CompletedEventPublications completedPublications;

    @BeforeEach
    void resetProbeState() {
        LifecycleProbeListener.STATE.set(ProbeState.FAIL);
        jdbcTemplate.update("DELETE FROM event_publication");
    }

    @Test
    @DisplayName("事务内暂存→失败保持未完成→重投成功→过期清理全链")
    void eventPublicationLifecycleEndToEnd() throws Exception {
        // ① 事务内发布：发布行与业务事务同事务写入（事务提交后可见），监听器 FAIL 态投递失败
        UUID eventId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(new LifecycleProbeEvent(eventId)));

        waitForRow(eventId);
        Integer incompleteCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL", Integer.class);
        assertThat(incompleteCount).as("监听器失败后发布必须保持未完成态").isEqualTo(1);

        // ② 编程式重投（EventOpsJob.retryStuck 同款 API）：切 SUCCEED 后按零阈值重投全部未完成发布
        LifecycleProbeListener.STATE.set(ProbeState.SUCCEED);
        incompletePublications.resubmitIncompletePublicationsOlderThan(Duration.ZERO);
        SUCCEED_LATCH.await(15, TimeUnit.SECONDS);
        assertThat(SUCCEED_LATCH.getCount()).as("重投后监听器应成功执行").isEqualTo(0);

        waitForCompletion(eventId);

        // ③ 清理：直调 CompletedEventPublications 清理 API 删除已完成发布
        // （真实任务阈值为 7 天，此处以零阈值验证删除语义）
        completedPublications.deletePublicationsOlderThan(Duration.ZERO);
        Integer remaining = jdbcTemplate.queryForObject("SELECT count(*) FROM event_publication", Integer.class);
        assertThat(remaining).as("零阈值清理后 event_publication 行数应归零").isEqualTo(0);
    }

    /** 轮询等待发布行落库（异步链路存在毫秒级相位差） */
    private void waitForRow(UUID eventId) throws InterruptedException {
        waitUntil(() -> countByEventId(eventId) >= 1, "发布行应在事务提交后落库");
    }

    /**
     * 轮询等待发布行标记完成（重投成功的完成态写入为异步）。行定位与 {@link #waitForRow} 同源
     * serialized_event：event_publication.id 为注册表随机 UUID，与事件自身标识无关。
     */
    private void waitForCompletion(UUID eventId) throws InterruptedException {
        waitUntil(
                () -> {
                    Integer done = jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM event_publication"
                                    + " WHERE serialized_event LIKE ? AND completion_date IS NOT NULL",
                            Integer.class,
                            "%" + eventId + "%");
                    return done != null && done >= 1;
                },
                "重投成功后 completion_date 应被写入");
    }

    private int countByEventId(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                Integer.class,
                "%" + eventId + "%");
        return count == null ? 0 : count;
    }

    /** 通用轮询：每 200ms 采样，20s 超时（不引入 awaitility 的仓库既有口径） */
    private void waitUntil(java.util.function.BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        org.junit.jupiter.api.Assertions.fail(message + "（20s 超时）");
    }
}
