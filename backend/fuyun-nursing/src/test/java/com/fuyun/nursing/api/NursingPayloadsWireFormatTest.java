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
 * 五类护理载荷 record 线格式锚（OutpatientEventContractTest 线格式用例同款口径）：
 * JSON 序列化字段名与 record 组件声明序逐位一致——消费方以 JSON 字段名取值，任一侧单独
 * 增删改名即此处红灯（desc contains 之外的构造侧锚，V800 冻结契约的出网线格式保障）。
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
