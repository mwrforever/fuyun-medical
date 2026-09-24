package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.service.IEventRegistryService;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.service.IPossibleDuplicateService;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * M02 EMPI 端到端验收 IT（P1 计划 §PR-2 三条验收 + DoD :104/:105）：迁移与种子断言、
 * 建档→归一→合并→拆分闭环、冻结拦截、事件经 fy.topic 可消费、脱敏与明文查阅双留痕、
 * 挂失失效、批量扫描幂等。容器三件套与 {@link ModulithEventLifecycleIT} 同款（tag 与 compose 一致）。
 *
 * <p>场景编排（@Order 串联，档案与合并状态跨用例累积属业务链路语义，同 AuthFlowIT 姿态）：
 * 用例 2/3 的业务动作按 AFTER_COMMIT 语义先于用例 4 出 MQ（HTTP 响应返回时信封已投递），
 * 用例 4 从既有自消费队列与 IT 队列确定性捕获信封——为此 @BeforeEach 停用全部 MQ 消费容器
 * （本 IT 不依赖任何消费者），防自事件缓存失效监听器与 {@code receive} 竞争消费同一队列。
 *
 * <p>双人角色实现说明：/api/v1/** 全端点需 Bearer 令牌且操作人恒注入登录身份（userId 字符串），
 * 唯一种子账号 admin(id=1) 无法自审自批——本 IT 经 JdbcTemplate 按 V303 形态播种第二账号
 * it-reviewer(id=2，复用 admin 口令哈希与 ADMIN 角色) 后走真实登录取令牌，create=admin("1")/
 * approve=reviewer("2") 双人角色在真栈成立，全程 HTTP 不混层。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmpiGovernanceIT extends FuyunStackITBase {

    /** TimescaleDB 容器：迁移历史/患者域表/事件台账断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：会话/两级患者缓存/幂等前置键的真实存储 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：挂载与 compose rabbitmq.conf 同语义的服务端默认队列类型配置（默认类型 quorum） */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 合成测试值：主档 A 全要素实名（18 位合成证件号，仅具 IT 意义） */
    private static final String ID_CARD_A = "110101198503121234";

    /** 合成测试值：从档 B 证件号（与 A 同姓名/性别/出生日期、证件号不同——弱标识 SUSPECT 触发对） */
    private static final String ID_CARD_B = "110101198503125678";

    /** 合成测试值：主档 A 手机号 */
    private static final String MOBILE_A = "13800001234";

    /** 合成测试值：主档 A 住址（脱敏断言锚点：保留到「市」） */
    private static final String ADDRESS_A = "北京市朝阳区合成验收路1号";

    /** 合成测试值：重复对共用姓名/性别/出生日期 */
    private static final String DUPLICATE_NAME = "陈志强";

    private static final String DUPLICATE_SEX = "1";
    private static final String DUPLICATE_BIRTH_DATE = "1985-03-12";

    /** 合成测试值：就诊卡卡面号（用例 6 挂失失效载体） */
    private static final String CARD_NO = "IT-VISIT-CARD-0001";

    /** 信封捕获等待上限：动作（HTTP 响应返回）时信封已 AFTER_COMMIT 投递，超时即链路断裂 */
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(10);

    /** 既有自消费队列名（q.<模块>.<事件>，与 PatientCacheInvalidationListener 同源常量拼接，禁手写字面量） */
    private static final String MERGED_QUEUE = MessagingConstants.QUEUE_PREFIX + PatientMessagingConstants.MODULE + "."
            + PatientMessagingConstants.EVENT_MERGED;

    private static final String FROZEN_QUEUE = MessagingConstants.QUEUE_PREFIX + PatientMessagingConstants.MODULE + "."
            + PatientMessagingConstants.EVENT_FROZEN;

    /** IT 测试消费队列名（created 不在自消费六事件集，由 ItQueueConfiguration 经治理构件声明） */
    private static final String IT_CREATED_QUEUE =
            MessagingConstants.QUEUE_PREFIX + "it." + PatientMessagingConstants.EVENT_CREATED;

    /** 登录令牌跨用例持有器：admin（业务动作 + 用例 5 ADMIN 豁免） */
    static final AtomicReference<String> ADMIN_TOKEN = new AtomicReference<>();

    /** 登录令牌跨用例持有器：reviewer（双人角色审批人） */
    static final AtomicReference<String> REVIEWER_TOKEN = new AtomicReference<>();

    /** 主档 A / 从档 B 患者主索引跨用例持有器（JSON 侧 Long→String，断言统一按字符串比较） */
    static final AtomicReference<Long> PATIENT_A = new AtomicReference<>();

    static final AtomicReference<Long> PATIENT_B = new AtomicReference<>();

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    IEventRegistryService eventRegistry;

    @Autowired
    IPossibleDuplicateService duplicateService;

    @Autowired
    RabbitListenerEndpointRegistry listenerRegistry;

    /**
     * IT 测试消费队列声明（复审 C3 定稿：仿 MessagingGovernanceIT——@TestConfiguration 内
     * @Bean 返回 Declarables，交 RabbitAdmin 幂等声明；直调 declareConsumerQueue 丢弃返回值不会声明队列）。
     * 声明副作用（registerSubscriber）会把 "it" 追加进 event_registry 的 created 订阅方，IT 环境可接受。
     */
    @TestConfiguration
    static class ItQueueConfiguration {

        @Bean
        Declarables itCreatedConsumerQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(new ConsumerQueueSpec("it", "patient.patient.created"));
        }
    }

    /**
     * 停用全部 MQ 消费容器：信封断言采用「既有自消费队列 receive 捕获」语义，不停用则
     * 监听器与 receive 竞争消费同一队列导致捕获不确定（本 IT 不依赖任何消费者，全停最简且确定；
     * RabbitListenerEndpointRegistry.stop() 幂等，上下文收尾重复停用无副作用）。
     */
    @BeforeEach
    void stopConsumersForEnvelopeCapture() {
        listenerRegistry.stop();
    }

    @Test
    @Order(1)
    @DisplayName("真库断言：flyway V100–V105 六行 success 且 V100 与 V503 升序并存，event_registry 9–16 八行登记")
    void freshDatabaseAppliesPatientMigrationsAndSeeds() {
        // 迁移红线：patient 段六迁移全部成功应用（V100–V105）
        Integer migrated = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.flyway_schema_history"
                        + " WHERE version IN ('100','101','102','103','104','105') AND success = TRUE",
                Integer.class);
        assertThat(migrated).as("V100–V105 应全部 success=t").isEqualTo(6);

        // 升序应用实证：V100（patient 段）installed_rank 必须小于 V503（后续 system 段），两行并存
        Map<String, Object> v100 = jdbcTemplate.queryForMap(
                "SELECT installed_rank FROM public.flyway_schema_history WHERE version = '100'");
        Map<String, Object> v503 = jdbcTemplate.queryForMap(
                "SELECT installed_rank FROM public.flyway_schema_history WHERE version = '503'");
        assertThat(((Number) v100.get("installed_rank")).intValue())
                .as("V100 应先于 V503 应用（installed_rank 升序）")
                .isLessThan(((Number) v503.get("installed_rank")).intValue());

        // 事件契约台账：V105 种子 id 9–16 八行，event_type 与消息常量三方一致（三段化字面量冻结）
        Integer seeded = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.event_registry WHERE id BETWEEN 9 AND 16", Integer.class);
        assertThat(seeded).as("V105 应登记 id 9–16 八行").isEqualTo(8);
        List<String> seededTypes = jdbcTemplate.queryForList(
                "SELECT event_type FROM integration.event_registry WHERE id BETWEEN 9 AND 16 ORDER BY id",
                String.class);
        assertThat(seededTypes)
                .containsExactly(
                        PatientMessagingConstants.EVENT_CREATED,
                        PatientMessagingConstants.EVENT_UPDATED,
                        PatientMessagingConstants.EVENT_MERGED,
                        PatientMessagingConstants.EVENT_SPLIT,
                        PatientMessagingConstants.EVENT_FROZEN,
                        PatientMessagingConstants.EVENT_UNFROZEN,
                        PatientMessagingConstants.EVENT_IDENTIFIER_CHANGED,
                        PatientMessagingConstants.EVENT_HEALTH_SUMMARY_UPDATED);
        // 登记服务口径：八事件 isRegistered 全真（先登记后发布红线的服务端落点）
        for (String eventType : seededTypes) {
            assertThat(eventRegistry.isRegistered(eventType))
                    .as("事件应已登记：%s", eventType)
                    .isTrue();
        }
    }

    @Test
    @Order(2)
    @DisplayName("建档→归一：全要素实名建档 A，同名同属性不同证件建档 B 触发 SUSPECT 新建+待审行，合并→审批→解析收敛→拆分恢复→再合并循环")
    void registrationNormalizesDuplicateArchivesThenMergeAndSplitRoundTrip() throws Exception {
        ensurePrincipals();

        // ① 建档 A（全要素实名）：无既有命中 → NO_MATCH 新建；非归一路径响应在 candidatePatientId
        //    槽位返回新建档案 id（register() 出参契约，单测 noMatchCreatesArchiveAndPublishesCreatedEvent 冻结）
        JsonNode createdA = postForJson(
                "/api/v1/patient/patients",
                ADMIN_TOKEN.get(),
                archiveBody(DUPLICATE_NAME, ID_CARD_A, MOBILE_A, ADDRESS_A, "IT-CONSENT-A"));
        assertThat(createdA.path("outcome").asText()).isEqualTo("NO_MATCH");
        long patientA = createdA.path("candidatePatientId").asLong();
        assertThat(patientA).isPositive();
        PATIENT_A.set(patientA);

        // ② 建档 B：弱标识 NAME_SEX_BIRTH(95≥阈值85) → SUSPECT 新建 + 待审行（匹配候选=A，经待审行断言）
        JsonNode createdB = postForJson(
                "/api/v1/patient/patients",
                ADMIN_TOKEN.get(),
                archiveBody(DUPLICATE_NAME, ID_CARD_B, "13900005678", "北京市海淀区合成验收路2号", "IT-CONSENT-B"));
        assertThat(createdB.path("outcome").asText()).isEqualTo("SUSPECT");
        assertThat(createdB.path("matchedRules").toString()).contains("NAME_SEX_BIRTH");
        long patientB = createdB.path("candidatePatientId").asLong();
        PATIENT_B.set(patientB);
        assertThat(patientB).isNotEqualTo(patientA);

        // ③ 待审工作台命中：a<b 规范化对 (min,max) 存在 PENDING 行，来源=建档实时检测
        long pairLow = Math.min(patientA, patientB);
        long pairHigh = Math.max(patientA, patientB);
        JsonNode pendingPage = getForJson("/api/v1/patient/possible-duplicates", ADMIN_TOKEN.get());
        JsonNode matchedRow = null;
        for (JsonNode row : pendingPage.path("content")) {
            if (row.path("patientIdA").asLong() == pairLow
                    && row.path("patientIdB").asLong() == pairHigh) {
                matchedRow = row;
            }
        }
        assertThat(matchedRow).as("待审列表应命中 (A,B) 规范化对").isNotNull();
        assertThat(matchedRow.path("status").asText()).isEqualTo("PENDING");
        assertThat(matchedRow.path("source").asText()).isEqualTo("REGISTER_SCAN");
        long duplicateId = matchedRow.path("id").asLong();

        // ④ 发起合并（经办=admin"1"，登录身份经 AuthTokenInterceptor 注入 OperatorContextHolder）
        JsonNode merge = postForJson(
                "/api/v1/patient/merges",
                ADMIN_TOKEN.get(),
                objectMapper
                        .createObjectNode()
                        .put("survivorPatientId", patientA)
                        .put("mergedPatientId", patientB)
                        .put("mergeReason", "IT 验收：同一自然人重复建档归并")
                        .put("possibleDuplicateId", duplicateId)
                        .toString());
        long mergeId = merge.path("id").asLong();
        assertThat(merge.path("status").asText()).isEqualTo("PROCESSING");
        assertThat(merge.path("operator").asText()).isEqualTo("1");

        // ⑤ 审批并执行合并（审批=reviewer"2"，双人角色成立）：从档置 MERGED + merged 事件
        JsonNode approved = postForJson("/api/v1/patient/merges/" + mergeId + "/approve", REVIEWER_TOKEN.get(), null);
        assertThat(approved.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(approved.path("approvedBy").asText()).isEqualTo("2");

        // 终审 Minor 补强：合并执行后 pre_snapshot 内容断言（此前仅断存在性——快照是拆分回挂唯一依据）
        String preSnapshot = jdbcTemplate.queryForObject(
                "SELECT pre_snapshot FROM patient.merge_record WHERE id = ?", String.class, mergeId);
        JsonNode snapshot = objectMapper.readTree(preSnapshot);
        assertThat(snapshot.path("name").asText()).isEqualTo(DUPLICATE_NAME);
        assertThat(snapshot.path("sex").asText()).isEqualTo(DUPLICATE_SEX);
        assertThat(snapshot.path("mobile").asText()).hasSize(64); // 盲索引 hex 入快照（禁明文，M02 红线 3）
        assertThat(snapshot.path("identifiers").isArray()).isTrue();
        assertThat(snapshot.path("identifiers").size()).isGreaterThanOrEqualTo(1);

        // ⑥ 归一解析：A 证件号解析收敛 A；B 证件号（合并后标识随重挂/指针链）同样收敛 A
        JsonNode resolveA = postForJson(
                "/api/v1/patient/identifiers/resolve", ADMIN_TOKEN.get(), resolveBody("ID_CARD", ID_CARD_A));
        assertThat(resolveA.path("resolvedPatientId").asLong()).isEqualTo(patientA);
        assertThat(resolveA.path("blocked").asBoolean()).isFalse();
        JsonNode resolveB = postForJson(
                "/api/v1/patient/identifiers/resolve", ADMIN_TOKEN.get(), resolveBody("ID_CARD", ID_CARD_B));
        assertThat(resolveB.path("resolvedPatientId").asLong()).isEqualTo(patientA);

        // ⑦ 从档状态：B 置 MERGED 且合并指针指向 A
        JsonNode detailB = getForJson("/api/v1/patient/patients/" + patientB, ADMIN_TOKEN.get());
        assertThat(detailB.path("status").asText()).isEqualTo("MERGED");
        assertThat(detailB.path("mergedIntoPatientId").asLong()).isEqualTo(patientA);

        // ⑧ 拆分恢复：标识按快照回挂 B，B 恢复 NORMAL、指针清空
        JsonNode split = postForJson("/api/v1/patient/merges/" + mergeId + "/split", ADMIN_TOKEN.get(), null);
        assertThat(split.path("status").asText()).isEqualTo("REVERSED");
        JsonNode restoredB = getForJson("/api/v1/patient/patients/" + patientB, ADMIN_TOKEN.get());
        assertThat(restoredB.path("status").asText()).isEqualTo("NORMAL");
        assertThat(restoredB.hasNonNull("mergedIntoPatientId")).isFalse();

        // ⑨ 拆分-再合并可循环边界：REVERSED 后同一从档可再次发起并审批成功
        //    （第二次 create 经办=reviewer"2" 与第二次 approve 操作人=admin"1" 不同，双人角色约束不回退）
        JsonNode mergeAgain = postForJson(
                "/api/v1/patient/merges",
                REVIEWER_TOKEN.get(),
                objectMapper
                        .createObjectNode()
                        .put("survivorPatientId", patientA)
                        .put("mergedPatientId", patientB)
                        .put("mergeReason", "IT 验收：拆分后再合并可循环边界")
                        .toString());
        long mergeIdAgain = mergeAgain.path("id").asLong();
        assertThat(mergeIdAgain).as("再合并应生成 NEW 合并记录").isNotEqualTo(mergeId);
        assertThat(mergeAgain.path("operator").asText()).isEqualTo("2");
        JsonNode approvedAgain =
                postForJson("/api/v1/patient/merges/" + mergeIdAgain + "/approve", ADMIN_TOKEN.get(), null);
        assertThat(approvedAgain.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(approvedAgain.path("approvedBy").asText()).isEqualTo("1");
    }

    @Test
    @Order(3)
    @DisplayName("冻结拦截：freeze A 后解析 blocked=true，unfreeze 成对恢复 blocked=false")
    void frozenPatientResolutionIsBlocked() throws Exception {
        long patientA = PATIENT_A.get();

        // 冻结：NORMAL→FROZEN（204），frozen 事件 AFTER_COMMIT 出 MQ（用例 4 捕获）
        ResponseEntity<String> frozen = exchange(
                "/api/v1/patient/patients/" + patientA + "/freeze",
                HttpMethod.POST,
                ADMIN_TOKEN.get(),
                objectMapper.createObjectNode().put("reason", "IT 验收：冻结拦截").toString());
        assertThat(frozen.getStatusCode().value()).isEqualTo(204);

        // 冻结期解析：主档 FROZEN → 拦截标记（业务模块拒绝新就诊的统一语义源）
        JsonNode blockedView = postForJson(
                "/api/v1/patient/identifiers/resolve", ADMIN_TOKEN.get(), resolveBody("ID_CARD", ID_CARD_A));
        assertThat(blockedView.path("resolvedPatientId").asLong()).isEqualTo(patientA);
        assertThat(blockedView.path("status").asText()).isEqualTo("FROZEN");
        assertThat(blockedView.path("blocked").asBoolean()).isTrue();
        assertThat(blockedView.path("blockReason").asText()).isEqualTo("患者档案已冻结");

        // 解冻成对恢复：FROZEN→NORMAL，拦截解除
        ResponseEntity<String> unfrozen = exchange(
                "/api/v1/patient/patients/" + patientA + "/unfreeze", HttpMethod.POST, ADMIN_TOKEN.get(), null);
        assertThat(unfrozen.getStatusCode().value()).isEqualTo(204);
        JsonNode restoredView = postForJson(
                "/api/v1/patient/identifiers/resolve", ADMIN_TOKEN.get(), resolveBody("ID_CARD", ID_CARD_A));
        assertThat(restoredView.path("status").asText()).isEqualTo("NORMAL");
        assertThat(restoredView.path("blocked").asBoolean()).isFalse();
        assertThat(restoredView.path("blockReason").asText()).isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("事件经 fy.topic 可消费：merged/frozen 既有自消费队列与 IT created 队列捕获信封并断言七字段")
    void patientEventsAreConsumableViaFyTopic() throws Exception {
        long patientA = PATIENT_A.get();
        long patientB = PATIENT_B.get();

        // merged 信封（用例 2 两次合并均入队，取首帧）：信封七字段冻结形态断言
        JsonNode merged = receiveEnvelope(MERGED_QUEUE);
        assertCommonEnvelopeFields(merged, PatientMessagingConstants.EVENT_MERGED);
        assertThat(merged.path("payload").path("survivorPatientId").asLong()).isEqualTo(patientA);
        assertThat(merged.path("payload").path("mergedPatientId").asLong()).isEqualTo(patientB);

        // frozen 信封（用例 3 冻结动作）：载荷单 id + 冻结原因，成对语义的 captured 侧
        JsonNode frozen = receiveEnvelope(FROZEN_QUEUE);
        assertCommonEnvelopeFields(frozen, PatientMessagingConstants.EVENT_FROZEN);
        assertThat(frozen.path("payload").path("patientId").asLong()).isEqualTo(patientA);
        assertThat(frozen.path("payload").path("reason").asText()).isNotBlank();

        // created 信封（建档动作，IT 队列无声明方竞争）：created 不在自消费六事件集，仅 IT 队列可消费
        JsonNode created = receiveEnvelope(IT_CREATED_QUEUE);
        assertCommonEnvelopeFields(created, PatientMessagingConstants.EVENT_CREATED);
        assertThat(created.path("payload").path("patientId").asLong()).isEqualTo(patientA);
        assertThat(created.path("payload").path("realNameFlag").asBoolean()).isTrue();
    }

    /**
     * 信封公共字段七字段口径断言（简报场景 4 写死口径，merged/frozen/created 三帧同款复用）：
     * eventId 可解析 UUID、producer=patient、eventType 与消息常量逐字一致、occurredAt 非空、
     * payloadVersion="1"、traceId 字段可空；payload 内容因事件而异由调用方按帧补充断言。
     *
     * @param envelope          捕获的信封 JSON 节点，非空；来源：receiveEnvelope 队列捕获帧
     * @param expectedEventType 期望事件类型；来源：PatientMessagingConstants 常量（三段化字面量）
     */
    private void assertCommonEnvelopeFields(JsonNode envelope, String expectedEventType) {
        assertThat(UUID.fromString(envelope.path("eventId").asText()))
                .as("eventId 应为可解析 UUID")
                .isNotNull();
        assertThat(envelope.path("producer").asText()).isEqualTo("patient");
        assertThat(envelope.path("eventType").asText()).isEqualTo(expectedEventType);
        assertThat(envelope.path("occurredAt").asText()).isNotBlank();
        assertThat(envelope.path("payloadVersion").asText()).isEqualTo("1");
        assertThat(envelope.has("traceId")
                        && (envelope.path("traceId").isTextual()
                                || envelope.path("traceId").isNull()))
                .as("traceId 字段可空（HTTP 线程发布取 MDC 值）")
                .isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("脱敏与明文查阅双留痕：详情恒脱敏，ADMIN 带令牌 unmask 返明文且 privacy_access_log +1")
    void sensitiveFieldsAreMaskedAndUnmaskIsDualLogged() throws Exception {
        long patientA = PATIENT_A.get();

        // 展示侧恒脱敏（I7 口径，ADMIN 也不例外）：各字段按种子规则掩码
        JsonNode detail = getForJson("/api/v1/patient/patients/" + patientA, ADMIN_TOKEN.get());
        assertThat(detail.path("patientId").asText()).isEqualTo(String.valueOf(patientA));
        assertThat(detail.path("name").asText()).isEqualTo("陈**");
        assertThat(detail.path("idCardNo").asText()).isEqualTo("110101********1234");
        assertThat(detail.path("mobile").asText()).isEqualTo("138****1234");
        assertThat(detail.path("address").asText()).isEqualTo("北京市******");
        assertThat(detail.path("birthDate").asText()).isEqualTo("1985-01-01");

        // 明文查阅前置：该患者台账行基线
        Integer accessLogsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM patient.privacy_access_log WHERE patient_id = ?", Integer.class, patientA);

        // ADMIN 豁免分支：带 Bearer 令牌（AuthTokenInterceptor 注入 roles 含 ADMIN）→ 200 明文
        // （fields 为数组节点：set 返回 JsonNode 会断链，经局部变量逐字段组装）
        ObjectNode unmaskRequest = objectMapper.createObjectNode();
        unmaskRequest.put("patientId", patientA);
        unmaskRequest.set(
                "fields",
                objectMapper.createArrayNode().add("name").add("idCardNo").add("mobile"));
        unmaskRequest.put("purpose", "IT 验收：明文查阅双留痕断言");
        JsonNode unmask = postForJson("/api/v1/patient/privacy/unmask", ADMIN_TOKEN.get(), unmaskRequest.toString());
        assertThat(unmask.path("values").path("name").asText()).isEqualTo(DUPLICATE_NAME);
        assertThat(unmask.path("values").path("idCardNo").asText()).isEqualTo(ID_CARD_A);
        assertThat(unmask.path("values").path("mobile").asText()).isEqualTo(MOBILE_A);

        // 台账侧留痕：privacy_access_log 恰 +1，操作人/目的/字段清单逐项落库（审计侧 SENSITIVE_QUERY 行由切面承载）
        Integer accessLogsAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM patient.privacy_access_log WHERE patient_id = ?", Integer.class, patientA);
        assertThat(accessLogsAfter).as("明文查阅必须落台账行").isEqualTo(accessLogsBefore + 1);
        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "SELECT operator_id, access_type, purpose, fields FROM patient.privacy_access_log"
                        + " WHERE patient_id = ? ORDER BY occurred_at DESC LIMIT 1",
                patientA);
        assertThat(logRow.get("operator_id")).isEqualTo("1");
        assertThat(logRow.get("access_type")).isEqualTo("UNMASK_QUERY");
        assertThat(logRow.get("purpose")).isEqualTo("IT 验收：明文查阅双留痕断言");
        assertThat(logRow.get("fields")).isEqualTo("name,idCardNo,mobile");
    }

    @Test
    @Order(6)
    @DisplayName("挂失解析立即失效：发卡→resolve 命中→挂失→同卡 resolve 404（默认配置一卡通未开户挂失成功）")
    void identifierLossInvalidatesResolution() throws Exception {
        long patientA = PATIENT_A.get();

        // 发卡并绑定 A（默认配置一卡通关闭，openIfEnabled 短路不开户）
        JsonNode card = postForJson(
                "/api/v1/patient/cards/issue",
                ADMIN_TOKEN.get(),
                objectMapper
                        .createObjectNode()
                        .put("patientId", patientA)
                        .put("cardNo", CARD_NO)
                        .toString());
        assertThat(card.path("cardNo").asText()).isEqualTo(CARD_NO);
        assertThat(card.path("status").asText()).isEqualTo("ACTIVE");

        // 挂失前解析：VISIT_CARD 标识 ACTIVE → 命中主档 A
        JsonNode resolved = postForJson(
                "/api/v1/patient/identifiers/resolve", ADMIN_TOKEN.get(), resolveBody("VISIT_CARD", CARD_NO));
        assertThat(resolved.path("resolvedPatientId").asLong()).isEqualTo(patientA);
        assertThat(resolved.path("blocked").asBoolean()).isFalse();

        // 挂失：ACTIVE→LOST（204；一卡通未开户的账户联动 PAT-1013 静默跳过，防 rollback-only 回归）
        ResponseEntity<String> loss =
                exchange("/api/v1/patient/cards/loss/" + CARD_NO, HttpMethod.POST, ADMIN_TOKEN.get(), null);
        assertThat(loss.getStatusCode().value()).isEqualTo(204);

        // 挂失后同卡解析立即失效：404 PAT-1001（ProblemDetail 契约）
        ResponseEntity<String> lost = exchange(
                "/api/v1/patient/identifiers/resolve",
                HttpMethod.POST,
                ADMIN_TOKEN.get(),
                resolveBody("VISIT_CARD", CARD_NO));
        assertThat(lost.getStatusCode().value()).isEqualTo(404);
        assertThat(objectMapper.readTree(lost.getBody()).path("errorCode").asText())
                .isEqualTo("PAT-1001");
    }

    @Test
    @Order(7)
    @DisplayName("批量扫描幂等：scanBatch 两次后待审行数不增，同对患者仅一条 PENDING（唯一索引兜底）")
    void batchScanIsIdempotentOnDuplicatePair() {
        long pairLow = Math.min(PATIENT_A.get(), PATIENT_B.get());
        long pairHigh = Math.max(PATIENT_A.get(), PATIENT_B.get());

        Integer pendingBefore = pendingRowCount();
        // 手动触发批量扫描两次（近 7 天档 × 同名评分；重复命中以 DuplicateKeyException 幂等静默）
        duplicateService.scanBatch();
        duplicateService.scanBatch();

        assertThat(pendingRowCount()).as("两次扫描后待审行数不得增加").isEqualTo(pendingBefore);
        Integer pairRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM patient.possible_duplicate"
                        + " WHERE patient_id_a = ? AND patient_id_b = ? AND status = 'PENDING'",
                Integer.class,
                pairLow,
                pairHigh);
        assertThat(pairRows).as("同对患者仅允许一条 PENDING 待审行").isEqualTo(1);
    }

    @Test
    @Order(8)
    @DisplayName("PERF-03 迁移断言：V900 success、pg_trgm 幂等在位、姓名 GIN trigram 索引落位，真栈姓名检索返回集与降序一致")
    void patientNameTrgmIndexInPlaceAndSearchResultUnchanged() throws Exception {
        // 迁移红线：V900（pg_trgm 扩展 + 姓名索引）随上下文启动的 Flyway 全量重放成功应用
        Integer migrated = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.flyway_schema_history WHERE version = '900' AND success = TRUE",
                Integer.class);
        assertThat(migrated).as("V900 应 success=t（pg_trgm 扩展 + GIN 索引迁移）").isEqualTo(1);

        // 扩展幂等实证：迁移内 CREATE EXTENSION IF NOT EXISTS 的重放语义——扩展已存在时再执行静默通过
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        String extVersion = jdbcTemplate.queryForObject(
                "SELECT extversion FROM pg_extension WHERE extname = 'pg_trgm'", String.class);
        assertThat(extVersion).as("pg_trgm 扩展必须已在库内启用").isNotBlank();

        // 索引交付物断言（PERF-02 NursingVitalSignFlowIT step9 同款形态）：GIN + gin_trgm_ops 落位，
        // 前导通配检索 '%kw%' 的索引扫描载体，防迁移静默缺失或 opclass 误建导致修复意图落空
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'patient' AND indexname = 'idx_patient_name_trgm'",
                String.class);
        assertThat(indexDef)
                .as("PERF-03：姓名 trigram GIN 索引必须存在且 opclass 为 gin_trgm_ops")
                .contains("USING gin")
                .contains("gin_trgm_ops");

        // 行为保持对照（真栈 + 索引在位）：关键词取姓名中段单字（非前缀，实证前导通配语义与单字边界），
        // 返回集与排序必须与既有契约一致——同关键词命中 step2 建立的同对档案且按 patientId 降序；
        // 检索结果经脱敏出口，断言锚定 patientId（禁依赖掩码姓名文本）；中文关键词经 UTF-8 显式
        // 百分号编码并以 URI 对象发起（绕开模板处理器二次编码的不确定性）
        long patientA = PATIENT_A.get();
        long patientB = PATIENT_B.get();
        URI searchUri = URI.create(restTemplate.getRootUri()
                + "/api/v1/patient/patients/search?keyword="
                + URLEncoder.encode(DUPLICATE_NAME.substring(1, 2), StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ADMIN_TOKEN.get());
        ResponseEntity<String> resp =
                restTemplate.exchange(searchUri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("姓名检索应 2xx，实况：%s", resp.getBody())
                .isTrue();
        JsonNode page = objectMapper.readTree(resp.getBody());
        List<Long> hitIds = new ArrayList<>();
        for (JsonNode row : page.path("content")) {
            hitIds.add(row.path("patientId").asLong());
        }
        List<Long> expected = patientA < patientB ? List.of(patientB, patientA) : List.of(patientA, patientB);
        assertThat(hitIds)
                .as("同关键词返回集与排序一致（LIKE 命中同对档案 + orderByDesc(patientId)，单字边界）")
                .isEqualTo(expected);
        assertThat(page.path("total").asLong()).as("同关键词命中总数一致").isEqualTo(2);
    }

    /**
     * 播种第二账号并完成双账号登录（幂等）：播种与登录编排收敛基类助手（终审 Minor「测试夹具收敛」
     * 换 FuyunStackITBase），reviewer 复用 admin 的 bcrypt 口令哈希与 ADMIN 角色绑定（V303 形态），
     * 操作人注入链路（AuthTokenInterceptor → OperatorContextHolder）在真栈成立双人角色。
     */
    private void ensurePrincipals() {
        if (ADMIN_TOKEN.get() != null && REVIEWER_TOKEN.get() != null) {
            return;
        }
        seedReviewerUser();
        ADMIN_TOKEN.compareAndSet(null, loginToken(ADMIN_LOGIN_NAME));
        REVIEWER_TOKEN.compareAndSet(null, loginToken(REVIEWER_LOGIN_NAME));
    }

    /**
     * 构造建档请求体（合成测试值，实名要素齐备）。
     *
     * @param name              姓名
     * @param idCardNo          证件号（18 位合成值）
     * @param mobile            手机号
     * @param address           住址
     * @param informedConsentRef 知情同意凭证引用（建档强制）
     * @return 建档请求 JSON 字符串
     * @throws Exception 序列化失败（测试构造错误显式失败）
     */
    private String archiveBody(String name, String idCardNo, String mobile, String address, String informedConsentRef)
            throws Exception {
        return objectMapper
                .createObjectNode()
                .put("name", name)
                .put("sex", DUPLICATE_SEX)
                .put("birthDate", DUPLICATE_BIRTH_DATE)
                .put("idCardNo", idCardNo)
                .put("mobile", mobile)
                .put("address", address)
                .put("registerChannel", "WINDOW")
                .put("archiveSource", "STANDARD")
                .put("informedConsentRef", informedConsentRef)
                .toString();
    }

    /**
     * 构造标识解析请求体。
     *
     * @param identifierType  标识类型（ID_CARD/VISIT_CARD 词表值）
     * @param identifierValue 标识值明文
     * @return 解析请求 JSON 字符串
     * @throws Exception 序列化失败（测试构造错误显式失败）
     */
    private String resolveBody(String identifierType, String identifierValue) throws Exception {
        return objectMapper
                .createObjectNode()
                .put("identifierType", identifierType)
                .put("identifierValue", identifierValue)
                .toString();
    }

    /**
     * 携 Bearer 令牌发送 JSON POST/无体 POST 并返回原始响应。
     *
     * @param uri   目标 URI（/api/v1 前缀），非空
     * @param token Bearer 令牌，可空（login 端点免认证传 null）
     * @param body  请求体 JSON 字符串，可空（无体动作端点传 null）
     * @return 原始 HTTP 响应（状态码与响应体可断言），非空
     */
    private ResponseEntity<String> exchange(String uri, HttpMethod method, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return restTemplate.exchange(uri, method, new HttpEntity<>(body, headers), String.class);
    }

    /**
     * 携令牌 POST 并解析 JSON 响应体（2xx 断言由调用方按端点契约自行承载）。
     *
     * @param uri   目标 URI，非空
     * @param token Bearer 令牌，非空
     * @param body  请求体 JSON 字符串，可空（无体动作端点传 null）
     * @return 响应体 JSON 节点，非空
     * @throws Exception 响应体非合法 JSON（契约断裂显式失败）
     */
    private JsonNode postForJson(String uri, String token, String body) throws Exception {
        ResponseEntity<String> response = exchange(uri, HttpMethod.POST, token, body);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("POST %s 应成功，实况：%s", uri, response.getBody())
                .isTrue();
        return objectMapper.readTree(response.getBody());
    }

    /**
     * 携令牌 GET 并解析 JSON 响应体。
     *
     * @param uri   目标 URI，非空
     * @param token Bearer 令牌，非空
     * @return 响应体 JSON 节点，非空
     * @throws Exception 响应体非合法 JSON（契约断裂显式失败）
     */
    private JsonNode getForJson(String uri, String token) throws Exception {
        ResponseEntity<String> response = exchange(uri, HttpMethod.GET, token, null);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("GET %s 应成功，实况：%s", uri, response.getBody())
                .isTrue();
        return objectMapper.readTree(response.getBody());
    }

    /**
     * 从指定队列捕获一帧信封并解析（动作在先、捕获在后的确定性语义：HTTP 响应返回时信封已经
     * AFTER_COMMIT 投递入队，自消费监听器已在 @BeforeEach 停用）。
     *
     * @param queueName 队列名，非空；来源：治理构件命名规则 q.<consumerModule>.<eventType>
     * @return 信封 JSON 节点，非空
     * @throws Exception 队列无帧（10s 超时，事件链路断裂）或帧非合法 JSON（CF-1 契约断裂）
     */
    private JsonNode receiveEnvelope(String queueName) throws Exception {
        Message message = rabbitTemplate.receive(queueName, RECEIVE_TIMEOUT.toMillis());
        assertThat(message).as("队列 %s 应在 %s 内捕获信封", queueName, RECEIVE_TIMEOUT).isNotNull();
        return objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    /** 当前 PENDING 待审行总数（幂等断言锚点） */
    private Integer pendingRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM patient.possible_duplicate WHERE status = 'PENDING'", Integer.class);
    }
}
