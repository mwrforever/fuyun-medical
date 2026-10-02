package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.nursing.api.AdverseEventReportedPayload;
import com.fuyun.nursing.api.AssessmentCompletedPayload;
import com.fuyun.nursing.api.InfusionCompletedPayload;
import com.fuyun.nursing.api.InfusionStartedPayload;
import com.fuyun.nursing.api.OrderExecutionCompletedPayload;
import com.fuyun.nursing.api.ShiftCompletedPayload;
import com.fuyun.nursing.api.TaskCompletedPayload;
import com.fuyun.nursing.api.TaskCreatedPayload;
import com.fuyun.nursing.api.TaskOverduePayload;
import com.fuyun.nursing.api.VitalSignRecordedPayload;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CF-6/M05 事件契约三方一致锚（GC4 红线的可执行化）：V800/V1109 种子 payload_desc ↔ Constants 事件字面量 ↔
 * nursing/api 载荷 record 组件名逐字同源——任何一侧单独漂移即红灯，CF-6 契约变更双向评审的第一道闸。
 * id 排段与登记形态断言同时守护 GC5（id 41–64 / 83 / status ACTIVE / 幂等 INSERT 形态）。
 */
class NursingEventContractTest {

    /** V800 种子 SQL 原文（三方一致的登记侧基准） */
    private static final String SEED_SQL = loadSeedSql();

    /** V1109 种子 SQL 原文（id 83 不良事件登记行，三方一致的登记侧基准——P2 PR-3 新增） */
    private static final String ADVERSE_SEED_SQL = loadAdverseSeedSql();

    /** 种子行提取正则：SELECT &lt;id&gt;, '&lt;event_type&gt;'（V800 全部 24 行统一 INSERT...SELECT 形态；
     * WHERE NOT EXISTS 守卫子查询内的 SELECT 1 FROM 无逗号跟引号，不误配） */
    private static final Pattern SEED_ROW_PATTERN = Pattern.compile("SELECT (\\d+), '([^']+)'");

    /** 登记行 id 下界（GC5：全局递增，接 outpatient 段最大 id 40） */
    private static final int FIRST_ID = 41;

    /** 登记行 id 上界（GC5：24 行收口于 64） */
    private static final int LAST_ID = 64;

    /** CF-6 冻结载体段收口 id（41–55：M04 事件族 12 + 审方回流 2 + execute-confirm 约定名 1） */
    private static final int FROZEN_TAG_LAST_ID = 55;

    /** M05 发布面 P1 实装段收口 id（56–60），其后 61–64 为 P1 占位登记段 */
    private static final int P1_IMPL_LAST_ID = 60;

    /** 种子行结构：id、事件字面量与语句段起点偏移（desc 段截取锚） */
    private record SeedRow(int id, String eventType, int start) {}

    /**
     * 装载 V800 种子 SQL 原文：文件缺失或不可读即契约锚失效，快败阻断契约测试。
     *
     * @return 种子 SQL 全文（UTF-8 解码）
     */
    private static String loadSeedSql() {
        // try-with-resources 关闭类路径流（缺失/不可读快败阻断，OutpatientEventContractTest 同款）
        try (InputStream seed = Optional.ofNullable(NursingEventContractTest.class.getResourceAsStream(
                        "/db/migration/nursing/V800__seed_nursing_event_registry.sql"))
                .orElseThrow(() -> new IllegalStateException("V800 种子文件缺失，契约锚失效"))) {
            return new String(seed.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("V800 种子文件不可读，契约锚失效", e);
        }
    }

    /**
     * 装载 V1109 种子 SQL 原文（id 83 不良事件登记行）：文件缺失或不可读即契约锚失效，快败阻断契约测试。
     *
     * @return 种子 SQL 全文（UTF-8 解码）
     */
    private static String loadAdverseSeedSql() {
        // try-with-resources 关闭类路径流（缺失/不可读快败阻断，与 V800 装载同款形态）
        try (InputStream seed = Optional.ofNullable(NursingEventContractTest.class.getResourceAsStream(
                        "/db/migration/nursing/V1109__seed_nursing_adverse_event_registry.sql"))
                .orElseThrow(() -> new IllegalStateException("V1109 种子文件缺失，契约锚失效"))) {
            return new String(seed.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("V1109 种子文件不可读，契约锚失效", e);
        }
    }

    @Test
    @DisplayName("V800 种子 24 行齐全：id 41–64 连续递增无重复（GC5 排段红线）")
    void v800SeedRowCountMatchesFrozenContract() {
        List<SeedRow> rows = seedRows();
        assertThat(rows).as("V800 登记行数与冻结契约 24 行不符").hasSize(24);
        List<Integer> expectedIds = new ArrayList<>();
        for (int id = FIRST_ID; id <= LAST_ID; id++) {
            expectedIds.add(id);
        }
        // 连续递增逐一在位：缺行、跳号、重复任一发生即契约排段破坏
        assertThat(rows.stream().map(SeedRow::id).toList()).containsExactlyElementsOf(expectedIds);
    }

    @Test
    @DisplayName("九条 nursing 事件字面量在种子中在位且与 Constants 常量逐一相等（三方一致第二、三方）")
    void seedEventTypesMatchMessagingConstants() {
        List<String> literals = List.of(
                NursingMessagingConstants.EVENT_VITAL_SIGN_RECORDED,
                NursingMessagingConstants.EVENT_ASSESSMENT_COMPLETED,
                NursingMessagingConstants.EVENT_TASK_CREATED,
                NursingMessagingConstants.EVENT_TASK_COMPLETED,
                NursingMessagingConstants.EVENT_SHIFT_COMPLETED,
                NursingMessagingConstants.EVENT_TASK_OVERDUE,
                NursingMessagingConstants.EVENT_INFUSION_STARTED,
                NursingMessagingConstants.EVENT_INFUSION_COMPLETED,
                NursingMessagingConstants.EVENT_ORDER_EXECUTION_COMPLETED);
        for (String literal : literals) {
            assertThat(SEED_SQL).as("V800 缺 nursing 登记行：%s", literal).contains("'" + literal + "'");
        }
        // 字面量侧同源反向锚：种子中登记的 nursing 事件恰为九条，多登漏登即与 Constants 漂移
        long nursingRows = seedRows().stream()
                .filter(row -> row.eventType().startsWith(NursingMessagingConstants.MODULE + "."))
                .count();
        assertThat(nursingRows).as("V800 nursing 段登记行数").isEqualTo(9);
    }

    @Test
    @DisplayName("登记行冻结标签分段在位：id 41–55 CF-6 载体 / 56–60 P1 实装 / 61–64 P1 占位 / 55 回签升级义务")
    void payloadDescContainsFrozenTag() {
        for (int id = FIRST_ID; id <= FROZEN_TAG_LAST_ID; id++) {
            assertThat(segmentOf(id)).as("id %d desc 缺 CF-6 冻结载体标签", id).contains("CF-6 冻结载体");
        }
        for (int id = FROZEN_TAG_LAST_ID + 1; id <= P1_IMPL_LAST_ID; id++) {
            assertThat(segmentOf(id)).as("id %d desc 缺 P1 实装标签", id).contains("P1 实装");
        }
        for (int id = P1_IMPL_LAST_ID + 1; id <= LAST_ID; id++) {
            assertThat(segmentOf(id)).as("id %d desc 缺 P1 占位登记标签", id).contains("P1 占位登记");
        }
        // id 55 执行回签 API 契约约定行：语义锚在位且字段级契约以更高版本 UPDATE 升级的义务注记在位
        assertThat(segmentOf(FROZEN_TAG_LAST_ID))
                .as("id 55 执行回签契约锚缺失")
                .contains("execute-confirm")
                .contains("字段级契约 pending M04 P2 定稿");
    }

    @Test
    @DisplayName("五类载荷 record 组件名拼接与对应 desc 逐字同源（GC4 三方一致第三方锚）")
    void payloadRecordComponentsMatchDesc() {
        assertComponentsInDesc(NursingMessagingConstants.EVENT_VITAL_SIGN_RECORDED, VitalSignRecordedPayload.class);
        assertComponentsInDesc(NursingMessagingConstants.EVENT_ASSESSMENT_COMPLETED, AssessmentCompletedPayload.class);
        assertComponentsInDesc(NursingMessagingConstants.EVENT_TASK_CREATED, TaskCreatedPayload.class);
        assertComponentsInDesc(NursingMessagingConstants.EVENT_TASK_COMPLETED, TaskCompletedPayload.class);
        assertComponentsInDesc(NursingMessagingConstants.EVENT_SHIFT_COMPLETED, ShiftCompletedPayload.class);
    }

    @Test
    @DisplayName("P2 五载荷 record 组件名与登记 desc 逐字一致：62/63/83 全串、61 子集投影、64 环节时点集")
    void p2PayloadRecordComponentsMatchRegisteredDesc() {
        // 全串形态：record 组件连续拼接串在 desc 中逐字出现（载荷与冻结 desc 字段面完全一致）
        assertComponentsInDesc(NursingMessagingConstants.EVENT_INFUSION_STARTED, InfusionStartedPayload.class);
        assertComponentsInDesc(NursingMessagingConstants.EVENT_INFUSION_COMPLETED, InfusionCompletedPayload.class);
        assertComponentsInDesc(
                NursingMessagingConstants.EVENT_ADVERSE_EVENT_REPORTED, AdverseEventReportedPayload.class);
        // 子集投影形态（id 61）：desc 冻结五字段 taskNo/patientId/wardId/planTime/escalationCount，
        // record 为其三字段投影——desc 冻结串逐字在位且每个组件名在 desc 中在位
        String overdueDesc = segmentOf(NursingMessagingConstants.EVENT_TASK_OVERDUE);
        assertThat(overdueDesc)
                .as("id 61 desc 缺冻结字段串（record 为其子集投影，desc 全串不可漂移）")
                .contains("taskNo/patientId/wardId/planTime/escalationCount");
        for (String component : componentNames(TaskOverduePayload.class)) {
            assertThat(overdueDesc).as("id 61 desc 缺组件 %s", component).contains(component);
        }
        // 环节时点集展开形态（id 64）：desc 以「环节时点集」概括四时点组件（V800 冻结文本不可改），
        // 冻结短语文面在位 + record 时点组件恰为四时点展开（signed/checked/started/finished），任何一侧漂移即红灯
        String executionDesc = segmentOf(NursingMessagingConstants.EVENT_ORDER_EXECUTION_COMPLETED);
        assertThat(executionDesc)
                .as("id 64 desc 缺环节时点集冻结拼接串")
                .contains("executionNo/m04PlanNo/m04OrderNo/patientId/visitId/环节时点集/executorId/overrideFlag");
        assertThat(componentNames(OrderExecutionCompletedPayload.class).stream()
                        .filter(name -> name.endsWith("At"))
                        .toList())
                .as("id 64 环节时点集展开面（时点组件清单）与冻结口径漂移")
                .containsExactly("signedAt", "checkedAt", "startedAt", "finishedAt");
    }

    @Test
    @DisplayName("V1109 id 83 登记：字面量/载荷五字段 desc/匿名与 M19 语义/幂等形态与 ACTIVE 三方一致")
    void adverseEventSeedRowMatchesConstantsAndPayload() {
        // 字面量三方第一方：种子登记行字面量与 Constants 逐字相等，登记主体与 id 排段在位
        assertThat(ADVERSE_SEED_SQL)
                .as("V1109 缺 id 83 登记行（字面量/主体/排段）")
                .contains("SELECT 83, '" + NursingMessagingConstants.EVENT_ADVERSE_EVENT_REPORTED + "', 'nursing'");
        // desc 冻结短语：载荷五字段组件串逐字在位（GC4 第三方锚——AdverseEventReportedPayload 组件名拼接）
        assertThat(ADVERSE_SEED_SQL)
                .as("V1109 desc 缺载荷五字段组件串")
                .contains(String.join("/", componentNames(AdverseEventReportedPayload.class)));
        // 匿名通道与 M19 消费语义冻结短语（brief 原文口径，desc 漂移即契约变更）
        assertThat(ADVERSE_SEED_SQL)
                .as("V1109 desc 缺匿名通道/M19 消费语义冻结短语")
                .contains("匿名上报不含 reporter")
                .contains("M19 护理质量指标消费（缺位登记）");
        // 登记状态 ACTIVE + 幂等 INSERT...SELECT...WHERE NOT EXISTS 形态，禁裸 VALUES 直插
        assertThat(ADVERSE_SEED_SQL).as("V1109 登记状态非 ACTIVE").contains("'ACTIVE'");
        assertThat(ADVERSE_SEED_SQL).as("V1109 缺幂等守卫").contains("WHERE NOT EXISTS");
        assertThat(ADVERSE_SEED_SQL).as("V1109 禁裸 VALUES 直插").doesNotContain("VALUES");
    }

    @Test
    @DisplayName("P2 消费订阅全集：13 条新增事件派生队列名 q.nursing.<event_type> 与列全口径一致（tick 键豁免）")
    void subscribedEventTypesDeriveThirteenP2ConsumerQueues() {
        String[] subscribed = NursingMessagingConstants.SUBSCRIBED_EVENT_TYPES;
        // 订阅全集 = P1 三条（patient 域）+ P2 十三条（inpatient 九/pharmacy 一/iot 三），无多登漏登
        assertThat(subscribed).as("订阅全集项数（P1 三 + P2 十三）").hasSize(16);
        List<String> p2Queues = Arrays.stream(subscribed)
                .filter(type -> !type.startsWith("patient."))
                .map(type -> NursingMessagingConstants.QUEUE_PREFIX + type)
                .toList();
        // 派生队列名逐字等于治理构件命名（QueueGovernorImpl：q.<消费者>.<事件>），brief 列全口径 13 条
        assertThat(p2Queues)
                .as("P2 消费队列列全面（inpatient 九 + dispense 一 + iot 告警三）")
                .containsExactlyInAnyOrder(
                        "q.nursing.inpatient.order.transferred",
                        "q.nursing.inpatient.order-plan.generated",
                        "q.nursing.inpatient.order.stopped",
                        "q.nursing.inpatient.order.cancelled",
                        "q.nursing.inpatient.visit.admitted",
                        "q.nursing.inpatient.visit.transferred",
                        "q.nursing.inpatient.visit.discharge-requested",
                        "q.nursing.inpatient.visit.discharged",
                        "q.nursing.inpatient.bed.changed",
                        "q.nursing.pharmacy.dispense.completed",
                        "q.nursing.iot.alarm.triggered",
                        "q.nursing.iot.alarm.escalated",
                        "q.nursing.iot.alarm.closed");
        // 命名规范：事件类型小写点分 ≥3 段（与 QueueGovernorImpl.EVENT_TYPE_PATTERN 同口径），队列名形如 q.nursing.<event_type>
        for (String type : subscribed) {
            assertThat(type).as("订阅事件类型命名违规：%s", type).matches("^[a-z][a-z0-9-]*(\\.[a-z0-9-]+){2,}$");
        }
        // tick 豁免注记：ROUTING_TASK_OVERDUE_TICK 为延迟队列到期转发路由键而非事件——不入
        // event_registry（延迟档位声明无先登记义务）、不入 SUBSCRIBED_EVENT_TYPES（不参与消费队列声明）
        assertThat(subscribed)
                .as("tick 键非事件，不得混入订阅全集")
                .doesNotContain(NursingMessagingConstants.ROUTING_TASK_OVERDUE_TICK);
        assertThat(NursingMessagingConstants.ROUTING_TASK_OVERDUE_TICK)
                .as("tick 路由键字面量（delay.task-overdue 档位到期转发目标）")
                .isEqualTo("nursing.task-overdue.tick");
        assertThat(NursingMessagingConstants.QUEUE_TASK_OVERDUE_TICK)
                .as("tick 消费队列名（Task 9 tick 监听器取用）")
                .isEqualTo("q.nursing.task-overdue.tick");
        // tick 键不出现在任何登记种子（先登记后订阅红线豁免的可执行注记）
        assertThat(SEED_SQL).as("V800 不得登记 tick 键（非事件）").doesNotContain("task-overdue.tick");
        assertThat(ADVERSE_SEED_SQL).as("V1109 不得登记 tick 键（非事件）").doesNotContain("task-overdue.tick");
    }

    @Test
    @DisplayName("V800 全部为 INSERT...SELECT...WHERE NOT EXISTS 幂等形态，无裸 VALUES 直插")
    void seedUsesIdempotentInsertForm() {
        List<Integer> insertStarts = indexOfAll("INSERT INTO integration.event_registry");
        assertThat(insertStarts).as("V800 INSERT 语句数与 24 行契约不符").hasSize(24);
        assertThat(indexOfAll("WHERE NOT EXISTS")).as("幂等守卫子句数与 INSERT 语句数不符").hasSameSizeAs(insertStarts);
        // 每条 INSERT 的语句段内均携带幂等守卫（守卫先于下一条 INSERT 出现）
        for (int i = 0; i < insertStarts.size(); i++) {
            int end = i + 1 < insertStarts.size() ? insertStarts.get(i + 1) : SEED_SQL.length();
            assertThat(SEED_SQL.substring(insertStarts.get(i), end))
                    .as("第 %d 条 INSERT 缺 WHERE NOT EXISTS 幂等守卫", i + 1)
                    .contains("WHERE NOT EXISTS");
        }
        assertThat(SEED_SQL).as("禁裸 INSERT INTO ... VALUES 直插").doesNotContain("VALUES");
    }

    /**
     * 提取 V800 全部登记行（按正则扫描 INSERT...SELECT 对）。
     *
     * @return 登记行清单（按文件出现序）
     */
    private List<SeedRow> seedRows() {
        Matcher matcher = SEED_ROW_PATTERN.matcher(SEED_SQL);
        List<SeedRow> rows = new ArrayList<>();
        while (matcher.find()) {
            rows.add(new SeedRow(Integer.parseInt(matcher.group(1)), matcher.group(2), matcher.start()));
        }
        return rows;
    }

    /**
     * 截取指定事件字面量所在登记行的语句段（V800 优先，V1109 兜底——P2 新增登记行的跨文件口径；
     * SELECT 起至本语句 WHERE NOT EXISTS 前，覆盖 desc 全文；偏移与截取同文本，防跨文件错位）。
     *
     * @param eventType 事件字面量，非空
     * @return 该行语句段文本
     */
    private String segmentOf(String eventType) {
        for (String seedSql : List.of(SEED_SQL, ADVERSE_SEED_SQL)) {
            Matcher matcher = SEED_ROW_PATTERN.matcher(seedSql);
            while (matcher.find()) {
                if (matcher.group(2).equals(eventType)) {
                    int end = seedSql.indexOf("WHERE NOT EXISTS", matcher.start());
                    return seedSql.substring(matcher.start(), end > 0 ? end : seedSql.length());
                }
            }
        }
        throw new IllegalStateException("V800/V1109 均缺登记行：" + eventType);
    }

    /**
     * 截取指定 id 所在登记行的语句段（口径同按字面量截取）。
     *
     * @param id 登记行 id（41–64）
     * @return 该行语句段文本
     */
    private String segmentOf(int id) {
        return seedRows().stream()
                .filter(row -> row.id() == id)
                .findFirst()
                .map(NursingEventContractTest::segmentText)
                .orElseThrow(() -> new IllegalStateException("V800 缺登记行：id " + id));
    }

    /**
     * 从登记行起点截取至最近一个 WHERE NOT EXISTS 前的语句段。
     *
     * @param row 登记行（含起点偏移），非空
     * @return 语句段文本
     */
    private static String segmentText(SeedRow row) {
        int end = SEED_SQL.indexOf("WHERE NOT EXISTS", row.start());
        return SEED_SQL.substring(row.start(), end > 0 ? end : SEED_SQL.length());
    }

    /**
     * 断言载荷 record 的组件名斜杠拼接串在对应事件 desc 中逐字出现（GC4 第三方锚）。
     *
     * @param eventType   事件字面量，非空
     * @param payloadType 载荷 record 类型，非空
     */
    private void assertComponentsInDesc(String eventType, Class<?> payloadType) {
        String joined = String.join(
                "/",
                Arrays.stream(payloadType.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList());
        assertThat(segmentOf(eventType))
                .as("事件 %s desc 缺组件串 %s（record 组件名与 desc 漂移）", eventType, joined)
                .contains(joined);
    }

    /**
     * 提取载荷 record 全部组件名（声明序）。
     *
     * @param payloadType 载荷 record 类型，非空
     * @return 组件名清单（声明序）
     */
    private static List<String> componentNames(Class<?> payloadType) {
        return Arrays.stream(payloadType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * 求取指定子串在种子文本中的全部出现起点。
     *
     * @param needle 子串，非空
     * @return 起点偏移清单（升序）
     */
    private List<Integer> indexOfAll(String needle) {
        List<Integer> positions = new ArrayList<>();
        int index = SEED_SQL.indexOf(needle);
        while (index >= 0) {
            positions.add(index);
            index = SEED_SQL.indexOf(needle, index + needle.length());
        }
        return positions;
    }
}
