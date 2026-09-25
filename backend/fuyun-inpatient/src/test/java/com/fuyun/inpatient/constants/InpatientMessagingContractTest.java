package com.fuyun.inpatient.constants;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.inpatient.api.payload.BedChangedPayload;
import com.fuyun.inpatient.api.payload.ConsultationPayload;
import com.fuyun.inpatient.api.payload.OrderAuditRejectedPayload;
import com.fuyun.inpatient.api.payload.OrderAuditedPayload;
import com.fuyun.inpatient.api.payload.OrderCancelledPayload;
import com.fuyun.inpatient.api.payload.OrderCreatedItem;
import com.fuyun.inpatient.api.payload.OrderCreatedPayload;
import com.fuyun.inpatient.api.payload.OrderExecutedPayload;
import com.fuyun.inpatient.api.payload.OrderPlanGeneratedPayload;
import com.fuyun.inpatient.api.payload.OrderRevokedPayload;
import com.fuyun.inpatient.api.payload.OrderTransferredPayload;
import com.fuyun.inpatient.api.payload.VisitAdmittedPayload;
import com.fuyun.inpatient.api.payload.VisitDischargeRequestedPayload;
import com.fuyun.inpatient.api.payload.VisitDischargedPayload;
import com.fuyun.inpatient.api.payload.VisitRegisteredPayload;
import com.fuyun.inpatient.api.payload.VisitTransferredPayload;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M04 事件契约三方一致锚（红线可执行化）：V901/V800 种子 payload_desc ↔ InpatientMessagingConstants
 * 事件字面量 ↔ inpatient/api/payload 载荷 record 组件名逐字同源——任何一侧单独漂移即红灯，
 * CF-6 契约变更双向评审的第一道闸。另守护：id 65–72 排段与幂等 INSERT 形态、id 55 W-33 字段级
 * 契约定稿锚、死订阅禁令反向（五订阅事件禁入发布面）、routing key 类型子键拼接格式。
 */
class InpatientMessagingContractTest {

    /** V901 升级与种子 SQL 原文（本模块 classpath 内，登记侧基准之一） */
    private static final String V901_SQL =
            loadClasspathSql("/db/migration/inpatient/V901__upgrade_and_seed_inpatient_event_registry.sql");

    /** V800 种子 SQL 原文（fuyun-nursing 模块资源不在本模块 classpath，走仓库同级模块源文件） */
    private static final String V800_SQL = loadSiblingSql(
            "fuyun-nursing/src/main/resources/db/migration/nursing/V800__seed_nursing_event_registry.sql");

    /** V605 种子 SQL 原文（fuyun-billing 模块资源——billing 段订阅事件两条的登记侧原文锚） */
    private static final String V605_SQL = loadSiblingSql(
            "fuyun-billing/src/main/resources/db/migration/billing/V605__seed_billing_event_registry.sql");

    /** 种子行提取正则：SELECT &lt;id&gt;, '&lt;event_type&gt;'（INSERT...SELECT 形态统一先例） */
    private static final Pattern SEED_ROW_PATTERN = Pattern.compile("SELECT (\\d+), '([^']+)'");

    /** V901 新增登记行 id 下界（GC 排段红线：全局递增接 nursing 段最大 id 64） */
    private static final int FIRST_ID = 65;

    /** V901 新增登记行 id 上界（八行收口于 72） */
    private static final int LAST_ID = 72;

    /** 发布面事件常量全集（20 = V800 既有 12 + V901 新增 8，字面量硬断言锚） */
    private static final List<String> PUBLISHED_EVENT_LITERALS = List.of(
            "inpatient.order.audited",
            "inpatient.order.transferred",
            "inpatient.order-plan.generated",
            "inpatient.order.stopped",
            "inpatient.order.cancelled",
            "inpatient.order.revoked",
            "inpatient.order.executed",
            "inpatient.visit.admitted",
            "inpatient.visit.transferred",
            "inpatient.visit.discharge-requested",
            "inpatient.visit.discharged",
            "inpatient.bed.changed",
            "inpatient.visit.registered",
            "inpatient.order.created",
            "inpatient.order.audit-rejected",
            "inpatient.consultation.requested",
            "inpatient.consultation.accepted",
            "inpatient.consultation.completed",
            "inpatient.consultation.overdue",
            "inpatient.consultation.cancelled");

    /** 发布面常量引用全集（与上面字面量清单按下标一一对应） */
    private static final List<String> PUBLISHED_EVENT_CONSTANTS = List.of(
            InpatientMessagingConstants.EVENT_ORDER_AUDITED,
            InpatientMessagingConstants.EVENT_ORDER_TRANSFERRED,
            InpatientMessagingConstants.EVENT_ORDER_PLAN_GENERATED,
            InpatientMessagingConstants.EVENT_ORDER_STOPPED,
            InpatientMessagingConstants.EVENT_ORDER_CANCELLED,
            InpatientMessagingConstants.EVENT_ORDER_REVOKED,
            InpatientMessagingConstants.EVENT_ORDER_EXECUTED,
            InpatientMessagingConstants.EVENT_VISIT_ADMITTED,
            InpatientMessagingConstants.EVENT_VISIT_TRANSFERRED,
            InpatientMessagingConstants.EVENT_VISIT_DISCHARGE_REQUESTED,
            InpatientMessagingConstants.EVENT_VISIT_DISCHARGED,
            InpatientMessagingConstants.EVENT_BED_CHANGED,
            InpatientMessagingConstants.EVENT_VISIT_REGISTERED,
            InpatientMessagingConstants.EVENT_ORDER_CREATED,
            InpatientMessagingConstants.EVENT_ORDER_AUDIT_REJECTED,
            InpatientMessagingConstants.EVENT_CONSULTATION_REQUESTED,
            InpatientMessagingConstants.EVENT_CONSULTATION_ACCEPTED,
            InpatientMessagingConstants.EVENT_CONSULTATION_COMPLETED,
            InpatientMessagingConstants.EVENT_CONSULTATION_OVERDUE,
            InpatientMessagingConstants.EVENT_CONSULTATION_CANCELLED);

    /** 种子行结构：id、事件字面量与语句段起点偏移（desc 段截取锚） */
    private record SeedRow(int id, String eventType, int start) {}

    @Test
    @DisplayName("V901 种子 8 行齐全：id 65–72 连续递增且全部 inpatient 前缀（排段红线）")
    void v901SeedRowsMatchPlannedRange() {
        List<SeedRow> rows = seedRows(V901_SQL);
        assertThat(rows).as("V901 登记行数与计划 8 行不符").hasSize(8);
        List<Integer> expectedIds = new ArrayList<>();
        for (int id = FIRST_ID; id <= LAST_ID; id++) {
            expectedIds.add(id);
        }
        // 连续递增逐一在位：缺行、跳号、重复任一发生即排段破坏
        assertThat(rows.stream().map(SeedRow::id).toList()).containsExactlyElementsOf(expectedIds);
        assertThat(rows.stream()
                        .map(SeedRow::eventType)
                        .allMatch(type -> type.startsWith(InpatientMessagingConstants.MODULE + ".")))
                .as("V901 登记行须全部为 inpatient 前缀")
                .isTrue();
    }

    @Test
    @DisplayName("20 个发布事件字面量与常量逐字相等，且在 V800/V901 登记文件中逐一在位（三方一致第一、二方）")
    void publishedConstantsMatchSeedLiterals() {
        for (int i = 0; i < PUBLISHED_EVENT_LITERALS.size(); i++) {
            assertThat(PUBLISHED_EVENT_CONSTANTS.get(i))
                    .as("第 %d 个发布常量与冻结字面量漂移", i + 1)
                    .isEqualTo(PUBLISHED_EVENT_LITERALS.get(i));
        }
        // V800 既有 12 条（id 41–52）在 nursing 段种子原文在位
        for (int i = 0; i < 12; i++) {
            assertThat(V800_SQL)
                    .as("V800 缺 inpatient 登记行：%s", PUBLISHED_EVENT_LITERALS.get(i))
                    .contains("'" + PUBLISHED_EVENT_LITERALS.get(i) + "'");
        }
        // V901 新增 8 条（id 65–72）在本模块种子原文在位
        for (int i = 12; i < PUBLISHED_EVENT_LITERALS.size(); i++) {
            assertThat(V901_SQL)
                    .as("V901 缺 inpatient 登记行：%s", PUBLISHED_EVENT_LITERALS.get(i))
                    .contains("'" + PUBLISHED_EVENT_LITERALS.get(i) + "'");
        }
        // 反向锚：两文件登记的 inpatient 前缀行恰为 13+8=21（V800 含 id 55 execute-confirm API 契约占行）
        assertThat(seedRows(V800_SQL).stream()
                        .filter(row -> row.eventType().startsWith(InpatientMessagingConstants.MODULE + "."))
                        .count())
                .as("V800 inpatient 段登记行数（12 事件 + 1 API 契约行）")
                .isEqualTo(13);
        assertThat(seedRows(V901_SQL).stream()
                        .filter(row -> row.eventType().startsWith(InpatientMessagingConstants.MODULE + "."))
                        .count())
                .as("V901 inpatient 段登记行数")
                .isEqualTo(8);
    }

    @Test
    @DisplayName("W-33 闭合锚：V901 UPDATE id 55 为字段级契约定稿（端点/请求/响应/幂等语义在位）")
    void v901UpgradesId55ToFieldLevelContract() {
        assertThat(V901_SQL)
                .as("缺 id 55 UPDATE 语句")
                .contains("WHERE id = 55 AND event_type = 'inpatient.order-plan.execute-confirm'");
        assertThat(updateSegment())
                .as("id 55 desc 缺执行回签端点锚")
                .contains("POST /api/v1/inpatient/order-plans/{no}/execute-confirm");
        assertThat(updateSegment()).as("id 55 desc 缺请求字段锚").contains("executorId(long,必填,执行护士员工ID)");
        assertThat(updateSegment())
                .as("id 55 desc 缺响应字段锚")
                .contains("planNo/m04OrderNo/orderStatus(迁移后医嘱头状态)/planStatus(迁移后计划状态)");
        assertThat(updateSegment()).as("id 55 desc 缺 W-33 闭合标注").contains("W-33 闭合");
        assertThat(updateSegment()).as("id 55 desc 缺幂等语义锚").contains("重复回签已 EXECUTED 计划返回当前状态、不迁移不发事件");
        // 审查修复环 R1 契约增补锚：跨日窗口守卫——长期医嘱计划穷尽判定受 end_at 限定
        assertThat(updateSegment())
                .as("id 55 desc 缺长期医嘱 end_at 守卫锚（修复环 R1 契约增补）")
                .contains("长期医嘱仅 end_at 到期后计划穷尽方判 COMPLETED，end_at 为空者经停嘱终结");
    }

    @Test
    @DisplayName("死订阅禁令反向：五订阅事件不在发布面，且订阅常量与 V605/V1002 登记名逐字一致")
    void subscribedEventsStayOutOfPublishFace() {
        List<String> subscribed = Arrays.asList(InpatientMessagingConstants.SUBSCRIBED_EVENT_TYPES);
        assertThat(subscribed).as("订阅事件全集须为五条").hasSize(5);
        for (String event : subscribed) {
            assertThat(PUBLISHED_EVENT_CONSTANTS)
                    .as("死订阅禁令反向：%s 禁入发布面常量", event)
                    .doesNotContain(event);
        }
        // 订阅字面量冻结（pharmacy 回执 2 + billing 3；先登记后订阅红线的消费侧锚）
        assertThat(subscribed)
                .containsExactly(
                        "pharmacy.medication-order.audit-completed",
                        "pharmacy.medication-order.audit-rejected",
                        "billing.deposit.changed",
                        "billing.settlement.completed",
                        "billing.arrears.approved");
        // 订阅登记原文锚（Task 2 minor 回接）：billing 两条（id 19/21）逐字在 V605 种子原文、
        // pharmacy 两条（id 53/54）逐字在 V800 种子原文——消费声明面与登记面单侧漂移即红灯；
        // billing.arrears.approved 的登记文件 V1002 归 billing 侧任务（Task 13）落地后补锚，
        // 当前以字面量冻结面承载
        assertThat(seedRows(V605_SQL).stream().map(SeedRow::eventType))
                .as("V605 登记行须含 billing 段两条订阅事件原文")
                .contains("billing.settlement.completed", "billing.deposit.changed");
        assertThat(V800_SQL)
                .as("V800 登记文件须含 pharmacy 段两条回执订阅事件原文")
                .contains("'pharmacy.medication-order.audit-completed'", "'pharmacy.medication-order.audit-rejected'");
    }

    @Test
    @DisplayName("routing key 类型子键拼接：事件名.子键，登记名不带子键")
    void withTypeKeyAppendsOrderTypeSubKey() {
        assertThat(InpatientMessagingConstants.withTypeKey(InpatientMessagingConstants.EVENT_ORDER_AUDITED, "drug"))
                .isEqualTo("inpatient.order.audited.drug");
        assertThat(InpatientMessagingConstants.withTypeKey(
                        InpatientMessagingConstants.EVENT_ORDER_CREATED, "discharge-med"))
                .isEqualTo("inpatient.order.created.discharge-med");
    }

    @Test
    @DisplayName("五类新载荷 record 组件名与 V901 对应 desc 逐字同源（三方一致第三方锚）")
    void payloadRecordComponentsMatchDesc() {
        assertComponentsInDesc(65, VisitRegisteredPayload.class);
        assertComponentsInDesc(66, OrderCreatedPayload.class);
        // 医嘱开立行项目子契约：desc items[] 段内组件串逐字同源（括号为全角，断言不含括号）
        assertThat(segmentOf(V901_SQL, 66))
                .as("id 66 desc 缺 items[] 组件串（OrderCreatedItem 与 desc 漂移）")
                .contains(componentJoin(OrderCreatedItem.class));
        assertComponentsInDesc(67, OrderAuditRejectedPayload.class);
        // ConsultationPayload 五态共用：五个会诊登记行 desc 均须含全组件串（按事件取用子集的共用契约本体）
        for (int id = 68; id <= LAST_ID; id++) {
            assertComponentsInDesc(id, ConsultationPayload.class);
        }
    }

    @Test
    @DisplayName("V800 段载荷锚：id 41/42/43/45/46/47/48/49/52 desc 与载荷 record 组件名逐字同源（V901 段锚同款写法）")
    void v800PayloadRecordComponentsMatchDesc() {
        // id 48：Task 3 审查 Minor-1 义务补锚；id 49/52：Task 4 转科/床位事件新载荷；
        // id 41/45/46：Task 6 审核通过/作废/撤回三事件新载荷（审核与控制域发布面）；
        // id 42：Task 7 转抄事件新载荷（转抄与执行计划域发布面）；
        // id 43/47：Task 8 计划拆分/执行回签两事件新载荷（日切分解与执行回签域发布面）；
        // id 50/51：Task 9 出院申请/出院终态两事件新载荷（出院管理域发布面）
        assertComponentsInDesc(V800_SQL, 41, OrderAuditedPayload.class);
        assertComponentsInDesc(V800_SQL, 42, OrderTransferredPayload.class);
        assertComponentsInDesc(V800_SQL, 43, OrderPlanGeneratedPayload.class);
        assertComponentsInDesc(V800_SQL, 45, OrderCancelledPayload.class);
        assertComponentsInDesc(V800_SQL, 46, OrderRevokedPayload.class);
        assertComponentsInDesc(V800_SQL, 47, OrderExecutedPayload.class);
        assertComponentsInDesc(V800_SQL, 48, VisitAdmittedPayload.class);
        assertComponentsInDesc(V800_SQL, 49, VisitTransferredPayload.class);
        assertComponentsInDesc(V800_SQL, 50, VisitDischargeRequestedPayload.class);
        assertComponentsInDesc(V800_SQL, 51, VisitDischargedPayload.class);
        assertComponentsInDesc(V800_SQL, 52, BedChangedPayload.class);
    }

    @Test
    @DisplayName("V901 为幂等 INSERT...SELECT...WHERE NOT EXISTS 形态且恰一条 UPDATE，无裸 VALUES 直插")
    void v901UsesIdempotentInsertForm() {
        List<Integer> insertStarts = indexOfAll(V901_SQL, "INSERT INTO integration.event_registry");
        assertThat(insertStarts).as("V901 INSERT 语句数与 8 行契约不符").hasSize(8);
        assertThat(indexOfAll(V901_SQL, "WHERE NOT EXISTS"))
                .as("幂等守卫子句数与 INSERT 语句数不符")
                .hasSameSizeAs(insertStarts);
        for (int i = 0; i < insertStarts.size(); i++) {
            int end = i + 1 < insertStarts.size() ? insertStarts.get(i + 1) : V901_SQL.length();
            assertThat(V901_SQL.substring(insertStarts.get(i), end))
                    .as("第 %d 条 INSERT 缺 WHERE NOT EXISTS 幂等守卫", i + 1)
                    .contains("WHERE NOT EXISTS");
        }
        assertThat(V901_SQL).as("禁裸 INSERT INTO ... VALUES 直插").doesNotContain("VALUES");
        assertThat(indexOfAll(V901_SQL, "UPDATE integration.event_registry"))
                .as("恰一条 UPDATE（id 55）")
                .hasSize(1);
    }

    /**
     * 装载本模块 classpath 内的迁移 SQL 原文：文件缺失或不可读即契约锚失效，快败阻断契约测试。
     *
     * @param resource classpath 资源路径，非空
     * @return 迁移 SQL 全文（UTF-8 解码）
     */
    private static String loadClasspathSql(String resource) {
        try (InputStream stream = Optional.ofNullable(
                        InpatientMessagingContractTest.class.getResourceAsStream(resource))
                .orElseThrow(() -> new IllegalStateException("迁移文件缺失，契约锚失效：" + resource))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("迁移文件不可读，契约锚失效：" + resource, e);
        }
    }

    /**
     * 装载仓库内同级模块的迁移 SQL 原文（V800 落 fuyun-nursing，不在本模块依赖面——跨模块契约锚
     * 走源文件直读；surefire 工作目录即模块 basedir，相对路径以仓库 backend/ 为根稳定成立）。
     *
     * @param repoRelativePath 自 backend/ 起的相对路径，非空
     * @return 迁移 SQL 全文（UTF-8 解码）
     */
    private static String loadSiblingSql(String repoRelativePath) {
        Path path = Path.of("..", repoRelativePath);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("同级模块迁移文件不可读，契约锚失效：" + path.toAbsolutePath(), e);
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
     * @param id  登记行 id（65–72）
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
     * 截取 V901 的 id 55 UPDATE 语句段（UPDATE 起至语句结尾，覆盖新 desc 全文）。
     *
     * @return UPDATE 语句段文本
     */
    private static String updateSegment() {
        int start = V901_SQL.indexOf("UPDATE integration.event_registry");
        int end = V901_SQL.indexOf(';', start);
        return V901_SQL.substring(start, end > 0 ? end : V901_SQL.length());
    }

    /**
     * 断言载荷 record 的组件名斜杠拼接串在 V901 指定登记行 desc 中逐字出现。
     *
     * @param id          V901 登记行 id（65–72），非空
     * @param payloadType 载荷 record 类型，非空
     */
    private static void assertComponentsInDesc(int id, Class<?> payloadType) {
        assertComponentsInDesc(V901_SQL, id, payloadType);
    }

    /**
     * 断言载荷 record 的组件名斜杠拼接串在指定种子 SQL 的登记行 desc 中逐字出现
     * （V800 段锚与 V901 段锚同款写法——段基座由调用方注入）。
     *
     * @param sql         种子 SQL 原文（V800/V901），非空
     * @param id          登记行 id，非空
     * @param payloadType 载荷 record 类型，非空
     */
    private static void assertComponentsInDesc(String sql, int id, Class<?> payloadType) {
        String joined = componentJoin(payloadType);
        assertThat(segmentOf(sql, id))
                .as("id %d desc 缺组件串 %s（record 组件名与 desc 漂移）", id, joined)
                .contains(joined);
    }

    /**
     * 求取 record 组件名的斜杠拼接串（反射取组件名，与 desc 字段清单逐字比对）；List 型
     * 组件追加 "[]"（desc 集合组件书写形态——如 id 43 planNos[]/planTimes[]、id 66 items[]，
     * 标量组件零后缀保持原样）。
     *
     * @param payloadType 载荷 record 类型，非空
     * @return 组件名斜杠拼接串（List 型带 [] 后缀）
     */
    private static String componentJoin(Class<?> payloadType) {
        return String.join(
                "/",
                Arrays.stream(payloadType.getRecordComponents())
                        .map(component ->
                                component.getName() + (List.class.isAssignableFrom(component.getType()) ? "[]" : ""))
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
