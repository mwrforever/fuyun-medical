package com.fuyun.outpatient.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CF-3/CF-5 事件契约三方一致锚（Global Constraints 红线的可执行化）：V204 种子 payload_desc ↔
 * api payload record 组件名 ↔ Constants 事件字面量逐字同源——任何一侧单独漂移即红灯，
 * 双向评审（CF-5 契约变更）的第一道闸。
 */
class OutpatientEventContractTest {

    /** V204 种子 SQL 原文（三方一致的登记侧基准） */
    private static final String SEED_SQL = loadSeedSql();

    /** V204 内 UPDATE 形态冻结面（id 23/25/31——V605/V702 占位行经本迁移 UPDATE 载荷冻结） */
    private static final List<String> UPDATE_FORM_EVENTS = List.of(
            OutpatientMessagingConstants.EVENT_ORDER_CREATED,
            OutpatientMessagingConstants.EVENT_ORDER_CHARGED,
            OutpatientMessagingConstants.EVENT_ORDER_CANCELLED);

    /**
     * 装载 V204 种子 SQL 原文：文件缺失或不可读即契约锚失效，快败阻断契约测试。
     *
     * @return 种子 SQL 全文（UTF-8 解码）
     */
    private static String loadSeedSql() {
        // try-with-resources 关闭类路径流（静态一次性加载语义不变；缺失/不可读仍快败阻断）
        try (InputStream seed = Optional.ofNullable(OutpatientEventContractTest.class.getResourceAsStream(
                        "/db/migration/outpatient/V204__seed_outpatient_event_registry.sql"))
                .orElseThrow(() -> new IllegalStateException("V204 种子文件缺失，契约锚失效"))) {
            return new String(seed.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("V204 种子文件不可读，契约锚失效", e);
        }
    }

    @Test
    @DisplayName("id 23/25/31/32/33/34/36/37/38/39/40 载荷 desc 与 record 组件逐字同源（发布面十一事件）")
    void registryDescriptionsMatchPublishedPayloadRecords() {
        assertDescContains(OutpatientMessagingConstants.EVENT_ORDER_CREATED, OrderCreatedPayload.COMPONENT_NAMES);
        assertDescContainsLine(
                OutpatientMessagingConstants.EVENT_ORDER_CREATED, OrderCreatedPayload.LINE_COMPONENT_NAMES);
        assertDescContains(OutpatientMessagingConstants.EVENT_ORDER_CHARGED, OrderChargedPayload.COMPONENT_NAMES);
        assertDescContains(OutpatientMessagingConstants.EVENT_ORDER_CANCELLED, OrderCancelledPayload.COMPONENT_NAMES);
        assertDescContains(OutpatientMessagingConstants.EVENT_VISIT_REGISTERED, VisitRegisteredPayload.COMPONENT_NAMES);
        assertDescContains(OutpatientMessagingConstants.EVENT_VISIT_FINISHED, VisitFinishedPayload.COMPONENT_NAMES);
        assertDescContains(OutpatientMessagingConstants.EVENT_VISIT_CANCELLED, VisitCancelledPayload.COMPONENT_NAMES);
        assertDescContains(
                OutpatientMessagingConstants.EVENT_APPOINTMENT_BOOKED, AppointmentBookedPayload.COMPONENT_NAMES);
        assertDescContains(
                OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED, AppointmentCancelledPayload.COMPONENT_NAMES);
        assertDescContains(
                OutpatientMessagingConstants.EVENT_APPOINTMENT_RESCHEDULED,
                AppointmentRescheduledPayload.COMPONENT_NAMES);
        assertDescContains(
                OutpatientMessagingConstants.EVENT_APPOINTMENT_TIMEOUT, AppointmentTimeoutPayload.COMPONENT_NAMES);
        assertDescContains(OutpatientMessagingConstants.EVENT_SCHEDULE_STOPPED, ScheduleStoppedPayload.COMPONENT_NAMES);
    }

    @Test
    @DisplayName("id 35/39 登记边界注记在位（仅登记无发布点 / 自产自消延迟回调）")
    void placeholderRowsAreRegisteredWithFreezeNotes() {
        // id 35 仅登记无发布点（id 27 先例）：登记行必须显式标注发布禁令，防消费任务误建队列
        assertThat(SEED_SQL).contains("'outpatient.visit.no-show'").contains("无发布点");
        // id 39 自产自消内部事件：延迟回调语义注记在位（fy.delay 档位到期回投 fy.topic）
        assertThat(SEED_SQL).contains("'outpatient.appointment.timeout'").contains("自产自消");
    }

    @Test
    @DisplayName("Constants 发布/登记字面量与 V204 登记行一一在位（字面量侧同源，订阅面禁 V204 重复登记）")
    void constantsLiteralsMatchRegistryRows() {
        List<String> registryLiterals = List.of(
                OutpatientMessagingConstants.EVENT_ORDER_CREATED,
                OutpatientMessagingConstants.EVENT_ORDER_CHARGED,
                OutpatientMessagingConstants.EVENT_ORDER_CANCELLED,
                OutpatientMessagingConstants.EVENT_VISIT_REGISTERED,
                OutpatientMessagingConstants.EVENT_VISIT_FINISHED,
                OutpatientMessagingConstants.EVENT_VISIT_CANCELLED,
                OutpatientMessagingConstants.EVENT_REGISTRY_VISIT_NO_SHOW,
                OutpatientMessagingConstants.EVENT_APPOINTMENT_BOOKED,
                OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED,
                OutpatientMessagingConstants.EVENT_APPOINTMENT_RESCHEDULED,
                OutpatientMessagingConstants.EVENT_APPOINTMENT_TIMEOUT,
                OutpatientMessagingConstants.EVENT_SCHEDULE_STOPPED);
        for (String literal : registryLiterals) {
            assertThat(SEED_SQL).as("V204 缺登记行：%s", literal).contains("'" + literal + "'");
        }
        // billing/pharmacy 六条订阅字面量登记于既有迁移（V605/V702），V204 禁重复登记
        assertThat(SEED_SQL)
                .doesNotContain("'" + OutpatientMessagingConstants.EVENT_SUB_BILLING_FEE_CREATED + "'")
                .doesNotContain("'" + OutpatientMessagingConstants.EVENT_SUB_BILLING_SETTLEMENT_COMPLETED + "'")
                .doesNotContain("'" + OutpatientMessagingConstants.EVENT_SUB_BILLING_REFUND_APPROVED + "'")
                .doesNotContain("'" + OutpatientMessagingConstants.EVENT_SUB_PHARMACY_PRESCRIPTION_CANCELLED + "'")
                .doesNotContain("'" + OutpatientMessagingConstants.EVENT_SUB_PHARMACY_DISPENSE_COMPLETED + "'")
                .doesNotContain("'" + OutpatientMessagingConstants.EVENT_SUB_PHARMACY_DISPENSE_RETURNED + "'");
    }

    @Test
    @DisplayName("api payload record 线格式字段名与 COMPONENT_NAMES 清单零漂移（发布方序列化锚）")
    void payloadWireFieldNamesMatchComponentNames() {
        // 消费方以 JSON 字段名取值：record 组件序列化顺序即 COMPONENT_NAMES 声明序，
        // 任一侧单独增删改名（未同步 COMPONENT_NAMES）即此处红灯——补齐 desc contains 之外的构造侧锚
        ObjectMapper mapper = new ObjectMapper();
        JsonNode created = mapper.valueToTree(new OrderCreatedPayload(
                "O2026092100001", 7L, "O2026092100001", List.of(new OrderCreatedPayload.Line("EXAM001", "1"))));
        assertFieldNames(created, OrderCreatedPayload.COMPONENT_NAMES);
        // 行组件锚：lines 首元素字段名与 LINE_COMPONENT_NAMES 逐位一致（嵌套线格式同源）
        assertLineFieldNames(created.get("lines").get(0));
        assertFieldNames(
                mapper.valueToTree(new OrderChargedPayload(
                        1L,
                        "JS2026092100001",
                        7L,
                        "O2026092100001",
                        List.of("O2026092100001"),
                        List.of("RX2026092100001"),
                        false)),
                OrderChargedPayload.COMPONENT_NAMES);
        assertFieldNames(
                mapper.valueToTree(new OrderCancelledPayload(
                        "O2026092100001", 7L, "O2026092100001", List.of("RX2026092100001"), "开单作废")),
                OrderCancelledPayload.COMPONENT_NAMES);
        assertFieldNames(
                mapper.valueToTree(new VisitRegisteredPayload("O2026092100001", 7L, "NORMAL", "DEPT01", "9001")),
                VisitRegisteredPayload.COMPONENT_NAMES);
        assertFieldNames(
                mapper.valueToTree(new VisitFinishedPayload("O2026092100001", 7L, "1", "9001")),
                VisitFinishedPayload.COMPONENT_NAMES);
        assertFieldNames(
                mapper.valueToTree(new VisitCancelledPayload("O2026092100001", 7L, "患者取消")),
                VisitCancelledPayload.COMPONENT_NAMES);
        assertFieldNames(
                mapper.valueToTree(new AppointmentBookedPayload(
                        "YY2026092100001", 7L, "20260922", "AM", "DEPT01", "NORMAL", "PORTAL")),
                AppointmentBookedPayload.COMPONENT_NAMES);
        assertFieldNames(
                mapper.valueToTree(new AppointmentCancelledPayload("YY2026092100001", 7L, "患者取消", false)),
                AppointmentCancelledPayload.COMPONENT_NAMES);
        assertFieldNames(
                mapper.valueToTree(new AppointmentRescheduledPayload(
                        "YY2026092100001", "YY2026092100002", 7L, "20260923", "0900")),
                AppointmentRescheduledPayload.COMPONENT_NAMES);
        assertFieldNames(
                mapper.valueToTree(new AppointmentTimeoutPayload("YY2026092100001", 7L, 42L)),
                AppointmentTimeoutPayload.COMPONENT_NAMES);
        assertFieldNames(
                mapper.valueToTree(new ScheduleStoppedPayload(42L, "20260922", "DEPT01", "9001", "医生临时停诊")),
                ScheduleStoppedPayload.COMPONENT_NAMES);
    }

    /**
     * 行组件断言：lines 行经数组首元素逐字核对（嵌套线格式字段名与 LINE_COMPONENT_NAMES 同源）。
     *
     * @param lineNode 行节点（lines 数组首元素），非空
     */
    private void assertLineFieldNames(JsonNode lineNode) {
        assertFieldNames(lineNode, OrderCreatedPayload.LINE_COMPONENT_NAMES);
    }

    /**
     * 顶层字段名断言：JSON 序列化字段名与 COMPONENT_NAMES 逐位一致。
     *
     * @param node     载荷线格式树，非空
     * @param expected record 组件名清单（与声明序同源），非空
     */
    private void assertFieldNames(JsonNode node, List<String> expected) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).as("线格式字段名与组件清单漂移").containsExactlyElementsOf(expected);
    }

    /** 顶层组件断言：事件字面量定位所在语句段，逐组件 contains */
    private void assertDescContains(String eventType, List<String> components) {
        String segment = segmentOf(eventType);
        for (String component : components) {
            assertThat(segment).as("desc 缺组件 %s（事件 %s）", component, eventType).contains(component);
        }
    }

    /** lines[]{...} 行组件断言（同段内逐字 contains） */
    private void assertDescContainsLine(String eventType, List<String> components) {
        String segment = segmentOf(eventType);
        for (String component : components) {
            assertThat(segment).contains(component);
        }
    }

    /** 截取事件字面量所在语句段：INSERT 形态=字面量起至最近一个 WHERE NOT EXISTS；UPDATE 形态=SET payload_desc 起至 WHERE id 子句 */
    private String segmentOf(String eventType) {
        int literal = SEED_SQL.indexOf("'" + eventType + "'");
        assertThat(literal).as("事件未登记于 V204：%s", eventType).isGreaterThan(-1);
        if (UPDATE_FORM_EVENTS.contains(eventType)) {
            // id 23/25/31 为 UPDATE 形态（V605/V702 已占行经本迁移冻结载荷）：事件字面量首次出现于
            // WHERE 子句、desc 在其之前，INSERT 口径截取必红——锚点回溯至本语句 `SET payload_desc =` 起点
            int set = SEED_SQL.lastIndexOf("SET payload_desc =", literal);
            assertThat(set).as("id 23/25/31 载荷 UPDATE 语句缺失于 V204：%s", eventType).isGreaterThan(-1);
            int end = SEED_SQL.indexOf("WHERE id = ", set);
            return SEED_SQL.substring(set, end > 0 ? end : SEED_SQL.length());
        }
        int end = SEED_SQL.indexOf("WHERE NOT EXISTS", literal);
        return SEED_SQL.substring(literal, end > 0 ? end : SEED_SQL.length());
    }
}
