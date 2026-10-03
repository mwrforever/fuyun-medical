package com.fuyun.pharmacy.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.enums.DispenseStatus;
import com.fuyun.pharmacy.enums.DispenseType;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M06 对外契约面测试（api 包）：错误码码值形态唯一（PH- 前缀 + 4 位数字，A.3-4）、
 * 药品变更广播载荷 record 契约（V702 id 30 冻结——组件序与 COMPONENT_NAMES 锚点一致，
 * 后续 Task 4 发布接线与消费方反序列化均依赖该锚点不漂移），以及 P2 PR-3 住院摆药衔接
 * 契约扩展（DispenseType/DispenseStatus 值域冻结与 DispenseCompletedPayload 组件名冻结）。
 */
class PharmacyApiContractTest {

    @Test
    @DisplayName("PH 码值唯一且形态合规（26 码位——PH-1023~1026 住院摆药域随 P2 PR-3 Task 8 增补，Task 6 22→23 同款 javadoc 预定扩位点）")
    void errorCodesAreUniqueAndWellFormed() {
        assertThat(Arrays.stream(PharmacyErrorCode.values()).map(PharmacyErrorCode::getCode))
                .allMatch(code -> code.matches("^PH-1\\d{3}$"))
                .doesNotHaveDuplicates()
                .hasSize(26);
    }

    @Test
    @DisplayName("DrugChangedPayload：三组件存取/等值/哈希契约 + COMPONENT_NAMES 与 record 组件序逐字同源")
    void drugChangedPayloadContract() {
        DrugChangedPayload payload = new DrugChangedPayload("1789000000000000001", "D-IT-001", "MAPPING");
        DrugChangedPayload same = new DrugChangedPayload("1789000000000000001", "D-IT-001", "MAPPING");

        assertThat(payload.drugId()).isEqualTo("1789000000000000001");
        assertThat(payload.drugCode()).isEqualTo("D-IT-001");
        assertThat(payload.changeType()).isEqualTo("MAPPING");
        assertThat(payload).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(payload.toString()).contains("D-IT-001").contains("MAPPING");

        // V702 id 30 冻结锚点：组件名清单与 record 声明序一致（漂移即本测试拦停，双向评审入口）
        List<String> declared = Arrays.stream(DrugChangedPayload.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(DrugChangedPayload.COMPONENT_NAMES).containsExactlyElementsOf(declared);
    }

    @Test
    @DisplayName("住院审方回执载荷契约：audit-completed/rejected 组件与 V800 id 53/54 desc 冻结读面逐字同源")
    void medicationAuditReplyPayloadsMatchFrozenRegistryComponents() {
        // V800（nursing 侧冻结迁移，pharmacy 类路径不可达）id 53/54 desc 组件串=M04 消费读面
        // （inpatient PharmacyAuditReplyListener 逐字读取），publish 侧 record 组件序必须逐字同源
        List<String> completed = Arrays.stream(MedicationAuditCompletedPayload.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(MedicationAuditCompletedPayload.COMPONENT_NAMES)
                .containsExactly("target", "auditNo", "auditOperator", "auditedAt")
                .containsExactlyElementsOf(completed);

        List<String> rejected = Arrays.stream(MedicationAuditRejectedPayload.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(MedicationAuditRejectedPayload.COMPONENT_NAMES)
                .containsExactly("target", "auditNo", "rejectReason", "auditOperator", "auditedAt")
                .containsExactlyElementsOf(rejected);
    }

    @Test
    @DisplayName("住院审方回执事件字面量：与 V800 id 53/54 登记名逐字一致（发布 eventType=路由键）")
    void medicationAuditReplyEventLiteralsMatchRegistry() {
        assertThat(PharmacyMessagingConstants.EVENT_MEDICATION_ORDER_AUDIT_COMPLETED)
                .isEqualTo("pharmacy.medication-order.audit-completed");
        assertThat(PharmacyMessagingConstants.EVENT_MEDICATION_ORDER_AUDIT_REJECTED)
                .isEqualTo("pharmacy.medication-order.audit-rejected");
    }

    @Test
    @DisplayName("调剂单类型值域冻结（V1110 住院两值增补）：OUTPATIENT/INPATIENT_DOSE/INPATIENT_PIVA")
    void dispenseTypeDomainFrozenWithInpatientValues() {
        // V1110 住院摆药衔接：单剂量口与 PIVAS 静配口两值增补（出院带药随 P3）；值域与序逐字冻结，
        // 与 dispense.dispense_type 列注释、V703 词表口径同源
        assertThat(Arrays.stream(DispenseType.values()).map(DispenseType::getCode))
                .containsExactly("OUTPATIENT", "INPATIENT_DOSE", "INPATIENT_PIVA");
    }

    @Test
    @DisplayName("发药单状态机值域冻结（住院链 CHECKED/DELIVERED 增补）：九值序与 Spec 状态机对齐，门诊链止于 ISSUED 不受影响")
    void dispenseStatusDomainFrozenWithInpatientChain() {
        // 共享前缀 CREATED→PICKING→PICKED 后分链：住院链 PICKED→CHECKED（病区核对）→DELIVERED
        // （病区签收，住院终态）；门诊链 PICKED→ISSUED（发药签名，门诊终态基点）→退药两态；
        // CANCELLED 殿后。住院链不用 ISSUED（语义区分：住院止于 DELIVERED 签收）
        assertThat(Arrays.stream(DispenseStatus.values()).map(DispenseStatus::getCode))
                .containsExactly(
                        "CREATED",
                        "PICKING",
                        "PICKED",
                        "CHECKED",
                        "DELIVERED",
                        "ISSUED",
                        "PART_RETURNED",
                        "FULL_RETURNED",
                        "CANCELLED");
    }

    @Test
    @DisplayName("DispenseCompletedPayload：十组件存取/等值契约 + COMPONENT_NAMES 与 record 组件序逐字同源（V702 原七组件 + V1111 住院追加）")
    void dispenseCompletedPayloadContract() {
        // 门诊发布面：住院衔接三字段（m04OrderNo/wardId/dispensePlanNo）传 null——门诊行住院
        // 维度无值；visitId 复用为门诊 O 型承载（双语义列，非新增组件）
        DispenseCompletedPayload outpatient = new DispenseCompletedPayload(
                "D20261002000001",
                "R20261002000001",
                "R20261002000001",
                1001L,
                "V202610020001",
                "OUTPATIENT",
                List.of(),
                null,
                null,
                null);
        assertThat(outpatient.dispenseNo()).isEqualTo("D20261002000001");
        assertThat(outpatient.visitId()).isEqualTo("V202610020001");
        assertThat(outpatient.m04OrderNo()).isNull();
        assertThat(outpatient.wardId()).isNull();
        assertThat(outpatient.dispensePlanNo()).isNull();

        // 住院摆药面：M05 签收衔接消费子集四字段全携值（visitId 复用为住院 I 型 14 位承载）
        DispenseCompletedPayload inpatient = new DispenseCompletedPayload(
                "D20261002000002",
                "R20261002000002",
                "R20261002000002",
                1002L,
                "V2026100210020001",
                "INPATIENT_DOSE",
                List.of(),
                "O20261002000001",
                "W01",
                "DP20261002000001");
        DispenseCompletedPayload inpatientSame = new DispenseCompletedPayload(
                "D20261002000002",
                "R20261002000002",
                "R20261002000002",
                1002L,
                "V2026100210020001",
                "INPATIENT_DOSE",
                List.of(),
                "O20261002000001",
                "W01",
                "DP20261002000001");
        assertThat(inpatient.m04OrderNo()).isEqualTo("O20261002000001");
        assertThat(inpatient.wardId()).isEqualTo("W01");
        assertThat(inpatient.dispensePlanNo()).isEqualTo("DP20261002000001");
        assertThat(inpatient).isEqualTo(inpatientSame).hasSameHashCodeAs(inpatientSame);

        // 组件名冻结（V1111 契约演进锚点）：清单序与 record 声明序一致且与冻结十名逐字同源
        List<String> declared = Arrays.stream(DispenseCompletedPayload.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(DispenseCompletedPayload.COMPONENT_NAMES)
                .containsExactly(
                        "dispenseNo",
                        "prescriptionId",
                        "rxNo",
                        "patientId",
                        "visitId",
                        "dispenseType",
                        "lines",
                        "m04OrderNo",
                        "wardId",
                        "dispensePlanNo")
                .containsExactlyElementsOf(declared);
    }
}
