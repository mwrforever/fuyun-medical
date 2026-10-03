package com.fuyun.nursing.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 十类护理载荷 record 线格式锚（OutpatientEventContractTest 线格式用例同款口径；P1 五类 + P2 五类）：
 * JSON 序列化字段名与 record 组件声明序逐位一致——消费方以 JSON 字段名取值，任一侧单独
 * 增删改名即此处红灯（desc contains 之外的构造侧锚，V800/V1109 冻结契约的出网线格式保障）。
 */
class NursingPayloadsWireFormatTest {

    @Test
    @DisplayName("五类载荷 record 序列化字段名与组件声明序逐位一致（出网线格式零漂移）")
    void payloadWireFieldNamesMatchComponentDeclarationOrder() {
        // 挂载 JSR-310 模块承接 Instant 组件（生产为 Boot 全局 mapper 同模块）；
        // 断言口径为字段名与声明序，日期值线格式由 Boot 序列化配置承载不在本锚范围
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        assertFieldNames(
                mapper.valueToTree(new VitalSignRecordedPayload(
                        42L, "Z2026092200001", Instant.parse("2026-09-22T08:30:00Z"), "BEDSIDE", "CONFIRMED", true)),
                List.of("patientId", "visitId", "measuredAt", "source", "reviewStatus", "abnormal"));
        assertFieldNames(
                mapper.valueToTree(
                        new AssessmentCompletedPayload(42L, "Z2026092200001", "PG2026092200001", "FALL", 45, "HIGH")),
                List.of("patientId", "visitId", "assessNo", "scaleType", "totalScore", "riskLevel"));
        assertFieldNames(
                mapper.valueToTree(new TaskCreatedPayload("H5_2026092200001", 42L, "Z2026092200001", "TURN", "ORDER")),
                List.of("taskNo", "patientId", "visitId", "taskType", "source"));
        assertFieldNames(
                mapper.valueToTree(new TaskCompletedPayload("H5_2026092200001", "COMPLETED")),
                List.of("taskNo", "status"));
        assertFieldNames(
                mapper.valueToTree(new ShiftCompletedPayload("JH2026092200001", "WARD01", "DAY", "9001", "9002")),
                List.of("handoverNo", "wardId", "shiftCode", "outgoingNurseId", "incomingNurseId"));
    }

    @Test
    @DisplayName("P2 五载荷 record 序列化字段名与组件声明序逐位一致（执行回执/输注起止/逾期/不良事件）")
    void p2PayloadWireFieldNamesMatchComponentDeclarationOrder() {
        // 挂载 JSR-310 模块承接 Instant 组件（生产为 Boot 全局 mapper 同模块）；断言口径同 P1 用例：
        // 字段名与声明序，不绑定日期值线格式；环节时点四组件（signed/checked/started/finished）为
        // id 64 desc「环节时点集」的展开面，顺序漂移即回执对账语义漂移
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        assertFieldNames(
                mapper.valueToTree(new OrderExecutionCompletedPayload(
                        "EX2026100200001",
                        "MP2026100200001",
                        "MO2026100200001",
                        42L,
                        "Z2026100200001",
                        Instant.parse("2026-10-02T01:00:00Z"),
                        Instant.parse("2026-10-02T01:05:00Z"),
                        Instant.parse("2026-10-02T01:10:00Z"),
                        Instant.parse("2026-10-02T02:00:00Z"),
                        9001L,
                        false)),
                List.of(
                        "executionNo",
                        "m04PlanNo",
                        "m04OrderNo",
                        "patientId",
                        "visitId",
                        "signedAt",
                        "checkedAt",
                        "startedAt",
                        "finishedAt",
                        "executorId",
                        "overrideFlag"));
        assertFieldNames(
                mapper.valueToTree(new InfusionStartedPayload(
                        "EX2026100200002",
                        42L,
                        "Z2026100200001",
                        "BAG2026100200001",
                        Instant.parse("2026-10-02T01:10:00Z"))),
                List.of("executionNo", "patientId", "visitId", "bagLabelCode", "startedAt"));
        assertFieldNames(
                mapper.valueToTree(new InfusionCompletedPayload(
                        "EX2026100200002", 42L, "Z2026100200001", Instant.parse("2026-10-02T02:00:00Z"))),
                List.of("executionNo", "patientId", "visitId", "endedAt"));
        assertFieldNames(
                mapper.valueToTree(new TaskOverduePayload("TK2026100200001", Instant.parse("2026-10-02T01:30:00Z"), 2)),
                List.of("taskNo", "planTime", "escalationCount"));
        assertFieldNames(
                mapper.valueToTree(new AdverseEventReportedPayload(
                        "AE2026100200001", "FALL", "II", "W01", Instant.parse("2026-10-02T00:30:00Z"))),
                List.of("eventNo", "category", "severityClass", "wardId", "occurredAt"));
    }

    /**
     * 字段名断言：JSON 序列化字段名与组件声明序逐位一致。
     *
     * @param node     载荷线格式树，非空
     * @param expected record 组件声明序清单，非空
     */
    private void assertFieldNames(JsonNode node, List<String> expected) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).as("线格式字段名与组件声明序漂移").containsExactlyElementsOf(expected);
    }
}
