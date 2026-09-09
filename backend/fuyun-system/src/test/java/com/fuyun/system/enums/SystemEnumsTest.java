package com.fuyun.system.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * RBAC/审计/字典全量枚举 code↔enum 双向映射测试（backend 宪法 A.2-7 枚举规范回归防线）。
 *
 * <p>断言三条契约对 12 个枚举的每一个常量成立：
 * ① getCode 返回与常量名一致的存储值且 code→枚举往返无损；② DB 映射注解 {@code @EnumValue} 落在
 * code 字段（MP 3.5.17 该注解仅支持 FIELD 目标）、JSON 输出注解 {@code @JsonValue} 落在 getCode 上；
 * ③ 未知 code 拒绝映射并抛 IllegalArgumentException（脏数据不得静默吞成 null）。
 */
class SystemEnumsTest {

    /** getCode 方法名：全部枚举签名一致，反射统一断言注解落位 */
    private static final String GETTER_NAME = "getCode";

    /** code 字段名：全部枚举一致（@EnumValue 落位处） */
    private static final String CODE_FIELD_NAME = "code";

    /** 未知 code 探针：任何枚举都不存在该值 */
    private static final String UNKNOWN_CODE = "__NO_SUCH_CODE__";

    /**
     * 逐常量断言目标枚举的双向映射与注解契约（对枚举全部常量循环生效）。
     *
     * @param fixture 枚举夹具（类型 + fromCode 方法引用），非空
     */
    private static void assertBidirectionalMapping(Fixture fixture) throws Exception {
        Method getter = fixture.type().getMethod(GETTER_NAME);
        Field codeField = fixture.type().getDeclaredField(CODE_FIELD_NAME);
        for (Object constant : fixture.type().getEnumConstants()) {
            String code = (String) getter.invoke(constant);
            // 存储值与常量名一致（本项目枚举 code 全部取常量名字面量，便于 SQL 种子与日志对照）
            assertThat(code).as("%s.%s 存储值应与常量名一致", fixture.type().getSimpleName(), constant)
                    .isEqualTo(((Enum<?>) constant).name());
            // code → 枚举往返无损
            assertThat(fixture.fromCode().apply(code))
                    .as("%s code=%s 应能映射回原常量", fixture.type().getSimpleName(), code)
                    .isSameAs(constant);
            // @EnumValue 落在 code 字段（MP 3.5.17 仅支持 FIELD 目标）、@JsonValue 落在 getCode（双契约各归其位）
            assertThat(codeField.isAnnotationPresent(EnumValue.class))
                    .as("%s.code 字段应标注 @EnumValue（MP DB 列映射）", fixture.type().getSimpleName())
                    .isTrue();
            assertThat(getter.isAnnotationPresent(JsonValue.class))
                    .as("%s.getCode 应标注 @JsonValue（JSON 输出 code）", fixture.type().getSimpleName())
                    .isTrue();
        }
    }

    /** 参数化夹具清单：12 个枚举全量（V300~V302 全部状态列值域 + 审计两枚举） */
    static List<Fixture> fixtures() {
        return List.of(
                new Fixture("UserType", UserType.class, UserType::fromCode),
                new Fixture("UserStatus", UserStatus.class, UserStatus::fromCode),
                new Fixture("EmployeeStatus", EmployeeStatus.class, EmployeeStatus::fromCode),
                new Fixture("OrgType", OrgType.class, OrgType::fromCode),
                new Fixture("OrgAttr", OrgAttr.class, OrgAttr::fromCode),
                new Fixture("OrgStatus", OrgStatus.class, OrgStatus::fromCode),
                new Fixture("RoleStatus", RoleStatus.class, RoleStatus::fromCode),
                new Fixture("DataScopeType", DataScopeType.class, DataScopeType::fromCode),
                new Fixture("PermissionType", PermissionType.class, PermissionType::fromCode),
                new Fixture("DictVersionStatus", DictVersionStatus.class, DictVersionStatus::fromCode),
                new Fixture("AuditActionType", AuditActionType.class, AuditActionType::fromCode),
                new Fixture("AuditResult", AuditResult.class, AuditResult::fromCode));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("fixtures")
    @DisplayName("全部枚举常量 code↔enum 双向映射无损且 @EnumValue/@JsonValue 注解落位正确")
    void allEnumConstantsRoundTripThroughCode(Fixture fixture) throws Exception {
        assertBidirectionalMapping(fixture);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("fixtures")
    @DisplayName("未知 code 拒绝映射并抛 IllegalArgumentException（脏数据不得静默吞）")
    void unknownCodeIsRejected(Fixture fixture) {
        assertThatThrownBy(() -> fixture.fromCode().apply(UNKNOWN_CODE))
                .as("%s 未知 code 应拒绝映射", fixture.type().getSimpleName())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(UNKNOWN_CODE);
    }

    /**
     * 枚举测试夹具：类型与 fromCode 方法引用配对，驱动参数化测试全覆盖。
     *
     * @param name     枚举简名（测试展示用）
     * @param type     枚举类型，非空
     * @param fromCode 枚举静态 fromCode 方法引用，非空
     */
    private record Fixture(
            String name, Class<? extends Enum<?>> type, Function<String, ? extends Enum<?>> fromCode) {

        @Override
        public String toString() {
            return name;
        }
    }
}
