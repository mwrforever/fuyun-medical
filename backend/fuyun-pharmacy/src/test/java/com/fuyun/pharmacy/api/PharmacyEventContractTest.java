package com.fuyun.pharmacy.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CF-5 事件契约三方一致锚（Global Constraints 红线的可执行化）：V702 种子 payload_desc ↔
 * api payload record 组件名 ↔ Constants 事件字面量逐字同源——任何一侧单独漂移即红灯，
 * 双向评审（CF-5 契约变更）的第一道闸。id 28 载荷 desc 权威面自 V1111 起为
 * 「V702 冻结原文 + V1111 UPDATE 追加句」合并面（契约演进只增不删，V702 文件禁改）。
 */
class PharmacyEventContractTest {

    /** V702 种子 SQL 原文（三方一致的登记侧基准） */
    private static final String SEED_SQL = loadSeedSql();

    /** V1111 载荷契约 UPDATE SQL 原文（id 28 desc 追加面基准，P2 PR-3 住院摆药衔接） */
    private static final String V1111_SQL = loadSql("V1111__update_dispense_completed_payload.sql");

    /**
     * 装载 V702 种子 SQL 原文：文件缺失或不可读即契约锚失效，快败阻断契约测试。
     *
     * @return 种子 SQL 全文（UTF-8 解码）
     */
    private static String loadSeedSql() {
        // try-with-resources 关闭类路径流（静态一次性加载语义不变；缺失/不可读仍快败阻断）
        try (InputStream seed = Optional.ofNullable(PharmacyEventContractTest.class.getResourceAsStream(
                        "/db/migration/pharmacy/V702__seed_pharmacy_event_registry.sql"))
                .orElseThrow(() -> new IllegalStateException("V702 种子文件缺失，契约锚失效"))) {
            return new String(seed.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("V702 种子文件不可读，契约锚失效", e);
        }
    }

    /**
     * 装载指定迁移 SQL 原文（V1111 起契约演进的登记侧追加面基准）。
     *
     * @param fileName pharmacy 迁移目录下文件名，非空
     * @return 迁移 SQL 全文（UTF-8 解码）
     */
    private static String loadSql(String fileName) {
        try (InputStream stream = Optional.ofNullable(
                        PharmacyEventContractTest.class.getResourceAsStream("/db/migration/pharmacy/" + fileName))
                .orElseThrow(() -> new IllegalStateException(fileName + " 迁移文件缺失，契约锚失效"))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(fileName + " 迁移文件不可读，契约锚失效", e);
        }
    }

    @Test
    @DisplayName("id 24/26/28/29/30 载荷 desc 与 record 组件逐字同源（publish 面五事件；id 28 权威面=V702+V1111 合并）")
    void registryDescriptionsMatchPublishedPayloadRecords() {
        assertDescContains("pharmacy.prescription.created", PrescriptionCreatedPayload.COMPONENT_NAMES);
        assertDescContainsLine("pharmacy.prescription.created", PrescriptionCreatedPayload.LINE_COMPONENT_NAMES);
        assertDescContains("pharmacy.prescription.cancelled", PrescriptionCancelledPayload.COMPONENT_NAMES);
        // 两侧 desc 组件全集对称覆盖（复审 N5）：completed/returned 顶层与行组件各断一次，禁单侧缺位
        // id 28 desc 权威面 = V702 冻结原文段 + V1111 UPDATE 追加段（契约演进只增不删——原七组件
        // 存于 V702、住院三组件名存于 V1111 追加句，合并后与 record 组件全集同源断言）
        String completedDesc = segmentOf("pharmacy.dispense.completed") + V1111_SQL;
        for (String component : DispenseCompletedPayload.COMPONENT_NAMES) {
            assertThat(completedDesc)
                    .as("desc 缺组件 %s（事件 pharmacy.dispense.completed，V702+V1111 合并面）", component)
                    .contains(component);
        }
        assertDescContainsLine("pharmacy.dispense.completed", DispenseCompletedPayload.LINE_COMPONENT_NAMES);
        assertDescContains("pharmacy.dispense.returned", DispenseReturnedPayload.COMPONENT_NAMES);
        assertDescContainsLine("pharmacy.dispense.returned", DispenseReturnedPayload.LINE_COMPONENT_NAMES);
        assertDescContains("pharmacy.drug.changed", DrugChangedPayload.COMPONENT_NAMES);
    }

    @Test
    @DisplayName("id 25/27/31 占位行在位且标注冻结边界（订阅/登记面三事件）")
    void placeholderRowsAreRegisteredWithFreezeNotes() {
        assertThat(SEED_SQL).contains("'outpatient.order.charged'").contains("PR-5");
        assertThat(SEED_SQL).contains("'pharmacy.prescription.rejected'").contains("无发布点");
        assertThat(SEED_SQL).contains("'outpatient.order.cancelled'").contains("PR-5");
    }

    @Test
    @DisplayName("id 28 载荷契约演进（V1111）：旧文本只增不删 + 住院四字段追加句逐字冻结 + UPDATE 守卫口径")
    void dispenseCompletedDescEvolutionAppendsInpatientSubsetOnly() {
        // 只增不删锚：V1111 新 desc 必须完整包含 V702 id 28 旧 desc 全文（既有消费方 billing/outpatient
        // 按原子集取用不受影响——新增仅可空字段，反序列化按组件名取值零删改）
        String oldDesc = "门诊发药完成（PR-4 实装）：dispenseNo/prescriptionId/rxNo/patientId/visitId/"
                + "dispenseType/lines[]{itemCode,batchNo,quantity,traceCodes[]}（与 api DispenseCompletedPayload "
                + "record 组件逐字同源）；M03 状态聚合（PR-5 订阅）、M13(本仓 billing) 执行占用标记 "
                + "DISPENSED（PR-4 已接线）";
        assertThat(V1111_SQL).contains(oldDesc);
        // 追加句逐字冻结（V1111 契约演进声明：M05 签收衔接消费子集，四字段名与 brief 原文逐字同源）
        assertThat(V1111_SQL)
                .contains("；住院摆药行增可空字段 m04OrderNo/visitId/wardId/dispensePlanNo" + "（M05 签收衔接消费子集，V1111 契约演进）");
        // UPDATE 守卫口径：id + event_type 双条件（V702 id 24 UPDATE 同款，防止 id 漂移误伤他行）
        assertThat(V1111_SQL).contains("WHERE id = 28 AND event_type = 'pharmacy.dispense.completed'");
        // V702 冻结文件不承载追加面（原文段无住院组件名——追加面唯一载体为 V1111）
        assertThat(segmentOf("pharmacy.dispense.completed")).doesNotContain("dispensePlanNo");
    }

    @Test
    @DisplayName("Constants 发布/订阅字面量与 V702 登记行一一在位（字面量侧同源）")
    void constantsLiteralsMatchRegistryRows() {
        List<String> registryLiterals = List.of(
                PharmacyMessagingConstants.EVENT_PRESCRIPTION_CREATED,
                PharmacyMessagingConstants.EVENT_PRESCRIPTION_CANCELLED,
                PharmacyMessagingConstants.EVENT_PRESCRIPTION_REJECTED,
                PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED,
                PharmacyMessagingConstants.EVENT_DISPENSE_RETURNED,
                PharmacyMessagingConstants.EVENT_DRUG_CHANGED,
                PharmacyMessagingConstants.EVENT_SUB_OUTPATIENT_ORDER_CHARGED,
                PharmacyMessagingConstants.EVENT_SUB_OUTPATIENT_ORDER_CANCELLED,
                PharmacyMessagingConstants.EVENT_SUB_BILLING_FEE_CREATED,
                PharmacyMessagingConstants.EVENT_SUB_BILLING_REFUND_APPROVED,
                PharmacyMessagingConstants.EVENT_SUB_SYSTEM_DICT_PUBLISHED,
                PharmacyMessagingConstants.EVENT_SUB_PATIENT_MERGED,
                PharmacyMessagingConstants.EVENT_SUB_PATIENT_SPLIT);
        for (String literal : registryLiterals) {
            // billing/patient/system 四条订阅字面量登记于既有迁移（V605/V105/V5），仅断言字面量
            // 与常量同源且 V702 无重复登记；pharmacy 七条须在 V702 原文在位
            if (literal.startsWith("pharmacy.")
                    || literal.startsWith("outpatient.order.charged")
                    || literal.startsWith("outpatient.order.cancelled")) {
                assertThat(SEED_SQL).contains("'" + literal + "'");
            }
        }
        assertThat(SEED_SQL).doesNotContain("'" + PharmacyMessagingConstants.EVENT_SUB_BILLING_FEE_CREATED + "'");
        assertThat(SEED_SQL).doesNotContain("'" + PharmacyMessagingConstants.EVENT_SUB_PATIENT_MERGED + "'");
    }

    /** 顶层组件断言：事件字面量定位所在 INSERT 段，逐组件 contains */
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

    /** 截取事件字面量所在语句段：INSERT 形态=字面量起至最近一个 WHERE NOT EXISTS；UPDATE 形态=SET payload_desc 起至 WHERE 子句 */
    private String segmentOf(String eventType) {
        int literal = SEED_SQL.indexOf("'" + eventType + "'");
        assertThat(literal).as("事件未登记于 V702：%s", eventType).isGreaterThan(-1);
        if (PharmacyMessagingConstants.EVENT_PRESCRIPTION_CREATED.equals(eventType)) {
            // id 24 为 UPDATE 形态（V605 已占行经本迁移冻结载荷）：事件字面量首次出现于 WHERE 子句、
            // desc 在其之前，INSERT 口径截取必红——锚点回溯至本语句 `SET payload_desc =` 起点
            int set = SEED_SQL.lastIndexOf("SET payload_desc =", literal);
            assertThat(set).as("id 24 载荷 UPDATE 语句缺失于 V702").isGreaterThan(-1);
            int end = SEED_SQL.indexOf("WHERE id = 24", set);
            return SEED_SQL.substring(set, end > 0 ? end : SEED_SQL.length());
        }
        int end = SEED_SQL.indexOf("WHERE NOT EXISTS", literal);
        return SEED_SQL.substring(literal, end > 0 ? end : SEED_SQL.length());
    }
}
