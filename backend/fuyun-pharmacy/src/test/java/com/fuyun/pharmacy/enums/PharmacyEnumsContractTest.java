package com.fuyun.pharmacy.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 六枚举全量契约测试（A.2-7 code↔enum 双向映射统一模板验证）：带 fromCode 的五枚举逐常量
 * 验证 code 与枚举名一致（V700 列注释直读口径）、fromCode/getCode 双向闭环、valueOf 同源、
 * 值域外 code 显式拒绝（脏数据禁静默）；DrugChangeType 为广播出向专用（无 fromCode——仅
 * @JsonValue 出向，Task 4 发布器消费），单独断言该形态。
 */
class PharmacyEnumsContractTest {

    /** 带 fromCode 的五枚举全集：新增枚举必须登记入列（漏登即本测试不再覆盖其映射契约，CI 兜底） */
    private static final List<Class<? extends Enum<?>>> PHARMACY_FROMCODE_ENUMS = List.of(
            DrugStatus.class, AntibacterialClass.class, HazardLevel.class, NhsaPayType.class, NarcoticClass.class);

    @Test
    @DisplayName("五枚举契约：code=常量名 + fromCode 双向闭环 + valueOf 同源 + 值域外拒绝")
    void fromCodeEnumsFollowCodeRoundTripContract() throws Exception {
        assertThat(PHARMACY_FROMCODE_ENUMS).hasSize(5);

        for (Class<? extends Enum<?>> type : PHARMACY_FROMCODE_ENUMS) {
            Method getCode = type.getMethod("getCode");
            Method fromCode = type.getMethod("fromCode", String.class);
            Method values = type.getMethod("values");
            Method valueOf = type.getMethod("valueOf", String.class);

            Object[] constants = (Object[]) values.invoke(null);
            assertThat(constants).as("%s 常量集非空", type.getSimpleName()).isNotEmpty();

            for (Object constant : constants) {
                String code = (String) getCode.invoke(constant);
                // 列值直读口径：code 与枚举常量名一致，DB 列无第二套词表
                assertThat(code).isEqualTo(((Enum<?>) constant).name());
                assertThat(fromCode.invoke(null, code)).isSameAs(constant);
                assertThat(valueOf.invoke(null, ((Enum<?>) constant).name())).isSameAs(constant);
            }

            // 值域外拒绝：fromCode/valueOf 均抛 IllegalArgumentException（反射调用统一包装为 InvocationTargetException）
            assertThatThrownBy(() -> fromCode.invoke(null, "__UNKNOWN__"))
                    .isInstanceOf(InvocationTargetException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> valueOf.invoke(null, "__UNKNOWN__"))
                    .isInstanceOf(InvocationTargetException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("DrugChangeType 契约：三值全集 + getCode 出向 + 设计上无 fromCode（仅 JSON 出向）")
    void drugChangeTypeIsJsonOnlyEnum() throws Exception {
        assertThat(DrugChangeType.values())
                .extracting(DrugChangeType::name)
                .containsExactly("CREATE", "UPDATE", "MAPPING");
        for (DrugChangeType type : DrugChangeType.values()) {
            // 广播 changeType 值域（V702 id 30 冻结）：code 与常量名一致
            assertThat(type.getCode()).isEqualTo(type.name());
        }
        // 出向专用枚举不设查询侧：反射证明 fromCode 方法不存在，防止误用形态漂移
        assertThatThrownBy(() -> DrugChangeType.class.getMethod("fromCode", String.class))
                .isInstanceOf(NoSuchMethodException.class);
    }
}
