package com.fuyun.pharmacy.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M06 对外契约面测试（api 包）：错误码码值形态唯一（PH- 前缀 + 4 位数字，A.3-4）与
 * 药品变更广播载荷 record 契约（V702 id 30 冻结——组件序与 COMPONENT_NAMES 锚点一致，
 * 后续 Task 4 发布接线与消费方反序列化均依赖该锚点不漂移）。
 */
class PharmacyApiContractTest {

    @Test
    @DisplayName("PH 码值唯一且形态合规（15 码位）")
    void errorCodesAreUniqueAndWellFormed() {
        assertThat(Arrays.stream(PharmacyErrorCode.values()).map(PharmacyErrorCode::getCode))
                .allMatch(code -> code.matches("^PH-1\\d{3}$"))
                .doesNotHaveDuplicates()
                .hasSize(15);
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
}
