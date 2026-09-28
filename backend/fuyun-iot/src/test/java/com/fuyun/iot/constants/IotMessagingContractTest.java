package com.fuyun.iot.constants;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.iot.api.payload.AlarmClosedPayload;
import com.fuyun.iot.api.payload.AlarmEscalatedPayload;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
import com.fuyun.iot.api.payload.BindingChangedPayload;
import com.fuyun.iot.api.payload.CallTriggeredPayload;
import com.fuyun.iot.api.payload.CommandCompletedPayload;
import com.fuyun.iot.api.payload.LinkageExecutedPayload;
import com.fuyun.iot.api.payload.TelemetryAnomalyPayload;
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
 * CF-7 事件契约三方一致锚（红线可执行化）：V1004 种子 payload_desc ↔ IotMessagingConstants
 * 事件字面量 ↔ iot/api/payload 载荷 record 组件名逐字同源——任何一侧单独漂移即红灯，
 * CF-7 契约变更双向评审的第一道闸（InpatientMessagingContractTest 同款形态）。另守护：
 * id 74–81 排段与幂等 INSERT 形态、既有 P0 发布事件（iot.device.status-changed）与
 * 自事件队列字面量不回归、队列命名前缀冻结。
 */
class IotMessagingContractTest {

    /** V1004 CF-7 事件种子 SQL 原文（本模块 classpath 内，登记侧基准） */
    private static final String V1004_SQL =
            loadClasspathSql("/db/migration/iot/V1004__seed_iot_cf7_event_registry.sql");

    /** V403 P0 事件种子 SQL 原文（本模块 classpath 内，既有事件不回归的登记侧基准） */
    private static final String V403_SQL = loadClasspathSql("/db/migration/iot/V403__seed_iot_event_registry.sql");

    /** 种子行提取正则：SELECT &lt;id&gt;, '&lt;event_type&gt;'（INSERT...SELECT 形态统一先例） */
    private static final Pattern SEED_ROW_PATTERN = Pattern.compile("SELECT (\\d+), '([^']+)'");

    /** V1004 新增登记行 id 下界（全局递增接 billing 段最大 id 73） */
    private static final int FIRST_ID = 74;

    /** V1004 新增登记行 id 上界（八行收口于 81） */
    private static final int LAST_ID = 81;

    /** CF-7 发布事件冻结字面量全集（与 V1004 登记名逐字一致，八条） */
    private static final List<String> CF7_EVENT_LITERALS = List.of(
            "iot.alarm.triggered",
            "iot.alarm.escalated",
            "iot.alarm.closed",
            "iot.binding.changed",
            "iot.telemetry.anomaly",
            "iot.command.completed",
            "iot.linkage.executed",
            "iot.call.triggered");

    /** CF-7 发布事件常量引用全集（与上面字面量清单按下标一一对应） */
    private static final List<String> CF7_EVENT_CONSTANTS = List.of(
            IotMessagingConstants.EVENT_ALARM_TRIGGERED,
            IotMessagingConstants.EVENT_ALARM_ESCALATED,
            IotMessagingConstants.EVENT_ALARM_CLOSED,
            IotMessagingConstants.EVENT_BINDING_CHANGED,
            IotMessagingConstants.EVENT_TELEMETRY_ANOMALY,
            IotMessagingConstants.EVENT_COMMAND_COMPLETED,
            IotMessagingConstants.EVENT_LINKAGE_EXECUTED,
            IotMessagingConstants.EVENT_CALL_TRIGGERED);

    /** CF-7 载荷 record 全集（与 V1004 登记行 id 74–81 按序一一对应，三方一致第三方锚） */
    private static final List<Class<?>> CF7_PAYLOAD_TYPES = List.of(
            AlarmTriggeredPayload.class,
            AlarmEscalatedPayload.class,
            AlarmClosedPayload.class,
            BindingChangedPayload.class,
            TelemetryAnomalyPayload.class,
            CommandCompletedPayload.class,
            LinkageExecutedPayload.class,
            CallTriggeredPayload.class);

    /** 种子行结构：id、事件字面量与语句段起点偏移（desc 段截取锚） */
    private record SeedRow(int id, String eventType, int start) {}

    @Test
    @DisplayName("V1004 种子 8 行齐全：id 74–81 连续递增且全部 iot 前缀（排段红线）")
    void v1004SeedRowsMatchPlannedRange() {
        List<SeedRow> rows = seedRows(V1004_SQL);
        assertThat(rows).as("V1004 登记行数与计划 8 行不符").hasSize(8);
        List<Integer> expectedIds = new ArrayList<>();
        for (int id = FIRST_ID; id <= LAST_ID; id++) {
            expectedIds.add(id);
        }
        // 连续递增逐一在位：缺行、跳号、重复任一发生即排段破坏
        assertThat(rows.stream().map(SeedRow::id).toList()).containsExactlyElementsOf(expectedIds);
        assertThat(rows.stream()
                        .map(SeedRow::eventType)
                        .allMatch(type -> type.startsWith(IotMessagingConstants.MODULE + ".")))
                .as("V1004 登记行须全部为 iot 前缀")
                .isTrue();
    }

    @Test
    @DisplayName("8 个 CF-7 事件字面量与常量逐字相等，且在 V1004 登记原文中逐一在位（三方一致第一、二方）")
    void cf7ConstantsMatchSeedLiterals() {
        for (int i = 0; i < CF7_EVENT_LITERALS.size(); i++) {
            assertThat(CF7_EVENT_CONSTANTS.get(i))
                    .as("第 %d 个 CF-7 发布常量与冻结字面量漂移", i + 1)
                    .isEqualTo(CF7_EVENT_LITERALS.get(i));
            assertThat(V1004_SQL)
                    .as("V1004 缺 iot 登记行：%s", CF7_EVENT_LITERALS.get(i))
                    .contains("'" + CF7_EVENT_LITERALS.get(i) + "'");
        }
        assertThat(IotMessagingConstants.QUEUE_PREFIX)
                .as("队列命名前缀冻结（q.&lt;消费者模块&gt;.&lt;事件类型&gt; 推导锚）")
                .isEqualTo("q.iot.");
    }

    @Test
    @DisplayName("既有 P0 发布面不回归：设备状态事件与自事件队列字面量冻结且在 V403 登记原文在位")
    void legacyP0FaceDoesNotRegress() {
        assertThat(IotMessagingConstants.EVENT_DEVICE_STATUS)
                .as("既有 P0 发布事件字面量漂移")
                .isEqualTo("iot.device.status-changed");
        assertThat(IotMessagingConstants.QUEUE_DEVICE_STATUS)
                .as("既有自事件队列字面量漂移")
                .isEqualTo("q.iot.iot.device.status-changed");
        assertThat(V403_SQL).as("V403 缺设备状态事件登记行").contains("'" + IotMessagingConstants.EVENT_DEVICE_STATUS + "'");
    }

    @Test
    @DisplayName("八类载荷 record 组件名与 V1004 对应 desc 逐字同源（三方一致第三方锚）")
    void payloadRecordComponentsMatchDesc() {
        for (int i = 0; i < CF7_PAYLOAD_TYPES.size(); i++) {
            int id = FIRST_ID + i;
            String joined = componentJoin(CF7_PAYLOAD_TYPES.get(i));
            // desc 字段链内嵌值域注记（如 alarmLevel(INFO|WARNING|CRITICAL)），剥离括号注记后比对组件串
            assertThat(stripValueDomainNotes(segmentOf(V1004_SQL, id)))
                    .as("id %d desc 缺组件串 %s（record 组件名与 desc 漂移）", id, joined)
                    .contains(joined);
        }
    }

    @Test
    @DisplayName("V1004 为幂等 INSERT...SELECT...WHERE NOT EXISTS 形态，无裸 VALUES 直插")
    void v1004UsesIdempotentInsertForm() {
        List<Integer> insertStarts = indexOfAll(V1004_SQL, "INSERT INTO integration.event_registry");
        assertThat(insertStarts).as("V1004 INSERT 语句数与 8 行契约不符").hasSize(8);
        assertThat(indexOfAll(V1004_SQL, "WHERE NOT EXISTS"))
                .as("幂等守卫子句数与 INSERT 语句数不符")
                .hasSameSizeAs(insertStarts);
        for (int i = 0; i < insertStarts.size(); i++) {
            int end = i + 1 < insertStarts.size() ? insertStarts.get(i + 1) : V1004_SQL.length();
            assertThat(V1004_SQL.substring(insertStarts.get(i), end))
                    .as("第 %d 条 INSERT 缺 WHERE NOT EXISTS 幂等守卫", i + 1)
                    .contains("WHERE NOT EXISTS");
        }
        assertThat(V1004_SQL).as("禁裸 INSERT INTO ... VALUES 直插").doesNotContain("VALUES");
    }

    /**
     * 装载本模块 classpath 内的迁移 SQL 原文：文件缺失或不可读即契约锚失效，快败阻断契约测试。
     *
     * @param resource classpath 资源路径，非空
     * @return 迁移 SQL 全文（UTF-8 解码）
     */
    private static String loadClasspathSql(String resource) {
        try (InputStream stream = Optional.ofNullable(IotMessagingContractTest.class.getResourceAsStream(resource))
                .orElseThrow(() -> new IllegalStateException("迁移文件缺失，契约锚失效：" + resource))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("迁移文件不可读，契约锚失效：" + resource, e);
        }
    }

    /**
     * 按正则提取指定 SQL 文本的全部登记行（INSERT...SELECT 对）。
     *
     * @param sql 迁移 SQL 原文，非空
     * @return 登记行清单（按文件出现序）
     */
    private static List<SeedRow> seedRows(String sql) {
        Matcher matcher = SEED_ROW_PATTERN.matcher(sql);
        List<SeedRow> rows = new ArrayList<>();
        while (matcher.find()) {
            rows.add(new SeedRow(Integer.parseInt(matcher.group(1)), matcher.group(2), matcher.start()));
        }
        return rows;
    }

    /**
     * 截取指定 id 所在登记行的语句段（SELECT 起至本语句 WHERE NOT EXISTS 前，覆盖 desc 全文）。
     *
     * @param sql 迁移 SQL 原文，非空
     * @param id  登记行 id（74–81）
     * @return 该行语句段文本
     */
    private static String segmentOf(String sql, int id) {
        return seedRows(sql).stream()
                .filter(row -> row.id() == id)
                .findFirst()
                .map(row -> {
                    int end = sql.indexOf("WHERE NOT EXISTS", row.start());
                    return sql.substring(row.start(), end > 0 ? end : sql.length());
                })
                .orElseThrow(() -> new IllegalStateException("迁移缺登记行：id " + id));
    }

    /**
     * 剥离 desc 字段链中的括号值域注记（如 alarmLevel(INFO|WARNING|CRITICAL)、changeType(BIND|UNBIND)），
     * 使组件串与 desc 的逐字比对不受注记干扰（注记本身另行承载于 record javadoc 值域说明）。
     *
     * @param segment 登记行语句段文本，非空
     * @return 剥离半角括号注记后的文本
     */
    private static String stripValueDomainNotes(String segment) {
        return segment.replaceAll("\\([^)]*\\)", "");
    }

    /**
     * 求取 record 组件名的斜杠拼接串（反射取组件名，与 desc 字段清单逐字比对）。
     *
     * @param payloadType 载荷 record 类型，非空
     * @return 组件名斜杠拼接串
     */
    private static String componentJoin(Class<?> payloadType) {
        return String.join(
                "/",
                Arrays.stream(payloadType.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList());
    }

    /**
     * 求取指定子串在文本中的全部出现起点。
     *
     * @param sql    迁移 SQL 原文，非空
     * @param needle 子串，非空
     * @return 起点偏移清单（升序）
     */
    private static List<Integer> indexOfAll(String sql, String needle) {
        List<Integer> positions = new ArrayList<>();
        int index = sql.indexOf(needle);
        while (index >= 0) {
            positions.add(index);
            index = sql.indexOf(needle, index + needle.length());
        }
        return positions;
    }
}
