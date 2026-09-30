package com.fuyun.iot.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * iot 领域全量枚举 code↔enum 双向映射测试（backend 宪法 A.2-7 枚举规范回归防线，SystemEnumsTest 同构）。
 *
 * <p>断言三条契约对全部枚举的每一个常量成立：
 * ① getCode 返回与常量名一致的存储值且 code→枚举往返无损；② DB 映射注解 {@code @EnumValue} 落在
 * code 字段（MP 3.5.17 该注解仅支持 FIELD 目标）、JSON 输出注解 {@code @JsonValue} 落在 getCode 上；
 * ③ 未知 code 拒绝映射并抛 BizException（IOT-1026，400——BE-C3-05 词表外收口；脏数据不得静默吞成
 * null）。断言迁移留痕（D-21 边界）：原锚 IllegalArgumentException 随 EX-19 模式级收口同步迁移为
 * BizException + 错误码，严格度不低于原断言（异常类型 + 错误码 + 消息三重锚定）。
 */
class IotEnumsTest {

    /** getCode 方法名：全部枚举签名一致，反射统一断言注解落位 */
    private static final String GETTER_NAME = "getCode";

    /** code 字段名：全部枚举一致（@EnumValue 落位处） */
    private static final String CODE_FIELD_NAME = "code";

    /** 未知 code 探针：任何枚举都不存在该值 */
    private static final String UNKNOWN_CODE = "__NO_SUCH_CODE__";

    /**
     * 符号承载 code 的枚举白名单：存储值按 Spec 词表直接承载比较符号（ThresholdOp 的
     * {@code >}/{@code <}，14-iot compare_op 列契约），不满足「code 与常量名一致」约定——
     * 仅豁免名字面量断言，往返无损/注解落位/词表外拒绝断言不豁免；白名单外偏离即红灯。
     */
    private static final Set<Class<? extends Enum<?>>> SYMBOLIC_CODE_ENUMS = Set.of(ThresholdOp.class);

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
            // 存储值与常量名一致（本项目枚举 code 全部取常量名字面量，便于 SQL 种子与日志对照；
            // 符号承载白名单（Spec 词表形态）豁免本条，其余断言不豁免）
            if (!SYMBOLIC_CODE_ENUMS.contains(fixture.type())) {
                assertThat(code)
                        .as("%s.%s 存储值应与常量名一致", fixture.type().getSimpleName(), constant)
                        .isEqualTo(((Enum<?>) constant).name());
            }
            // code → 枚举往返无损
            assertThat(fixture.fromCode().apply(code))
                    .as("%s code=%s 应能映射回原常量", fixture.type().getSimpleName(), code)
                    .isSameAs(constant);
            // @EnumValue 落在 code 字段（MP 3.5.17 仅支持 FIELD 目标）、@JsonValue 落在 getCode（双契约各归其位）
            assertThat(codeField.isAnnotationPresent(EnumValue.class))
                    .as("%s.code 字段应标注 @EnumValue（MP DB 列映射）", fixture.type().getSimpleName())
                    .isTrue();
            assertThat(getter.isAnnotationPresent(JsonValue.class))
                    .as(
                            "%s.getCode 应标注 @JsonValue（JSON 输出 code）",
                            fixture.type().getSimpleName())
                    .isTrue();
        }
    }

    /** 参数化夹具清单（A.2-7 规范回归防线；EX-19 补齐至模块全部 24 词表枚举，含命令/网关/联动/物模型/告警域） */
    static List<Fixture> fixtures() {
        return List.of(
                new Fixture("DeviceStatus", DeviceStatus.class, DeviceStatus::fromCode),
                new Fixture("DeviceAccessMode", DeviceAccessMode.class, DeviceAccessMode::fromCode),
                new Fixture("BindType", BindType.class, BindType::fromCode),
                new Fixture("BindingStatus", BindingStatus.class, BindingStatus::fromCode),
                new Fixture("ConsumeErrorStage", ConsumeErrorStage.class, ConsumeErrorStage::fromCode),
                new Fixture("ConsumeErrorStatus", ConsumeErrorStatus.class, ConsumeErrorStatus::fromCode),
                new Fixture("TelemetryQuality", TelemetryQuality.class, TelemetryQuality::fromCode),
                new Fixture("TelemetrySource", TelemetrySource.class, TelemetrySource::fromCode),
                new Fixture("CommandStatus", CommandStatus.class, CommandStatus::fromCode),
                new Fixture("CommandDeliverMode", CommandDeliverMode.class, CommandDeliverMode::fromCode),
                new Fixture("CommandSafetyLevel", CommandSafetyLevel.class, CommandSafetyLevel::fromCode),
                new Fixture("GatewayMode", GatewayMode.class, GatewayMode::fromCode),
                new Fixture("GatewayStatus", GatewayStatus.class, GatewayStatus::fromCode),
                new Fixture("AlarmLevel", AlarmLevel.class, AlarmLevel::fromCode),
                new Fixture("AlarmRuleType", AlarmRuleType.class, AlarmRuleType::fromCode),
                new Fixture("AlarmStatus", AlarmStatus.class, AlarmStatus::fromCode),
                new Fixture("LinkageTriggerSource", LinkageTriggerSource.class, LinkageTriggerSource::fromCode),
                new Fixture("LinkageActionType", LinkageActionType.class, LinkageActionType::fromCode),
                new Fixture("LinkageActionResult", LinkageActionResult.class, LinkageActionResult::fromCode),
                new Fixture("MetricCategory", MetricCategory.class, MetricCategory::fromCode),
                new Fixture("MetricDataType", MetricDataType.class, MetricDataType::fromCode),
                new Fixture("MismatchStrategy", MismatchStrategy.class, MismatchStrategy::fromCode),
                new Fixture("ProductSyncStatus", ProductSyncStatus.class, ProductSyncStatus::fromCode),
                new Fixture("ThresholdOp", ThresholdOp.class, ThresholdOp::fromCode));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("fixtures")
    @DisplayName("全部枚举常量 code↔enum 双向映射无损且 @EnumValue/@JsonValue 注解落位正确")
    void allEnumConstantsRoundTripThroughCode(Fixture fixture) throws Exception {
        assertBidirectionalMapping(fixture);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("fixtures")
    @DisplayName("未知 code 拒绝映射并抛 BizException IOT-1026（词表外收口，脏数据不得静默吞）")
    void unknownCodeIsRejected(Fixture fixture) {
        // 词表外收口锚定（BE-C3-05）：异常类型 + 错误码 + 入参回显三重断言，严格度不低于原 IAE 锚
        assertThatThrownBy(() -> fixture.fromCode().apply(UNKNOWN_CODE))
                .as("%s 未知 code 应拒绝映射", fixture.type().getSimpleName())
                .isInstanceOf(BizException.class)
                .hasMessageContaining(UNKNOWN_CODE)
                .extracting("errorCode.code", InstanceOfAssertFactories.STRING)
                .isEqualTo(IotErrorCode.ENUM_CODE_INVALID.getCode());
    }

    /**
     * 枚举测试夹具：类型与 fromCode 方法引用配对，驱动参数化测试全覆盖。
     *
     * @param name     枚举简名（测试展示用）
     * @param type     枚举类型，非空
     * @param fromCode 枚举静态 fromCode 方法引用，非空
     */
    private record Fixture(String name, Class<? extends Enum<?>> type, Function<String, ? extends Enum<?>> fromCode) {

        @Override
        public String toString() {
            return name;
        }
    }
}
