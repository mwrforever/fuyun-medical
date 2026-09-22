package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.nursing.api.AssessmentCompletedPayload;
import com.fuyun.nursing.api.ShiftCompletedPayload;
import com.fuyun.nursing.api.TaskCompletedPayload;
import com.fuyun.nursing.api.TaskCreatedPayload;
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
 * CF-6/M05 事件契约三方一致锚（GC4 红线的可执行化）：V800 种子 payload_desc ↔ Constants 事件字面量 ↔
 * nursing/api 载荷 record 组件名逐字同源——任何一侧单独漂移即红灯，CF-6 契约变更双向评审的第一道闸。
 * id 排段与登记形态断言同时守护 GC5（id 41–64 / status ACTIVE / 幂等 INSERT 形态）。
 */
class NursingEventContractTest {

    /** V800 种子 SQL 原文（三方一致的登记侧基准） */
    private static final String SEED_SQL = loadSeedSql();

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
     * 截取指定事件字面量所在登记行的语句段（SELECT 起至本语句 WHERE NOT EXISTS 前，覆盖 desc 全文）。
     *
     * @param eventType 事件字面量，非空
     * @return 该行语句段文本
     */
    private String segmentOf(String eventType) {
        return seedRows().stream()
                .filter(row -> row.eventType().equals(eventType))
                .findFirst()
                .map(NursingEventContractTest::segmentText)
                .orElseThrow(() -> new IllegalStateException("V800 缺登记行：" + eventType));
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
