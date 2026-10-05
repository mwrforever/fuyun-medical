package com.fuyun.patient.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import java.lang.reflect.Method;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 患者读面 SENSITIVE_QUERY 审计注解在位冻结测试（W-47）：fuyun-patient 七 controller 的
 * 11 个 GET 端点（档案详情/检索/标识清单/健康摘要/卡详情/账户详情/流水/疑似重复待审/
 * 隐私授权清单/脱敏规则清单/查阅台账）触达患者敏感数据读路径，必须挂
 * {@code @AuditLog(actionType = AuditActionType.SENSITIVE_QUERY)} 留痕（等保三级红线面）。
 *
 * <p>冻结语义：注解误删不会产生任何编译期或运行期信号（切面仅拦截在位注解），等保留痕
 * 即静默失守——本测试以反射断言锚定注解在位与动作类型精确匹配（非空断言锚定审计配置面，
 * 注解误删即红）。形参类型逐方法按 controller 实签名核实（@PathVariable/@RequestParam
 * 实参类型，含包装类型差异）。审计切面行为（落库失败不阻断、上下文拦截）已有
 * AuditLogAspect 测试覆盖，本测试不重测切面行为，仅断言注解在位。
 */
class PatientReadAuditAnnotationTest {

    /**
     * 11 端点装配清单：controller 类 + 方法名 + 形参类型数组（与实签名逐一核对）。
     *
     * @return 端点参数流（每项=controller、方法名、形参类型、端点中文描述）
     */
    static Stream<Arguments> sensitiveQueryEndpoints() {
        return Stream.of(
                Arguments.of(
                        PatientController.class,
                        "detail",
                        new Class<?>[] {long.class},
                        "患者档案详情 GET /patients/{patientId}"),
                Arguments.of(
                        PatientController.class,
                        "search",
                        new Class<?>[] {String.class, int.class, int.class},
                        "患者检索 GET /patients/search"),
                Arguments.of(
                        PatientIdentifierController.class,
                        "list",
                        new Class<?>[] {long.class},
                        "标识清单 GET /patients/{patientId}/identifiers"),
                Arguments.of(
                        HealthController.class,
                        "summary",
                        new Class<?>[] {long.class},
                        "健康档案摘要 GET /patients/{patientId}/health-summary"),
                Arguments.of(CardController.class, "detail", new Class<?>[] {String.class}, "卡详情 GET /cards/{cardNo}"),
                Arguments.of(
                        CardAccountController.class,
                        "detail",
                        new Class<?>[] {long.class},
                        "一卡通账户详情 GET /card-accounts/{id}"),
                Arguments.of(
                        CardAccountController.class,
                        "txns",
                        new Class<?>[] {long.class, int.class, int.class},
                        "一卡通账户流水 GET /card-accounts/{id}/txns"),
                Arguments.of(
                        DuplicateMergeController.class,
                        "list",
                        new Class<?>[] {String.class, int.class, int.class},
                        "疑似重复待审列表 GET /possible-duplicates"),
                Arguments.of(
                        PrivacyController.class, "auths", new Class<?>[] {long.class}, "隐私授权清单 GET /privacy-auths"),
                Arguments.of(PrivacyController.class, "maskRules", new Class<?>[0], "脱敏规则清单 GET /privacy-mask-rules"),
                Arguments.of(
                        PrivacyController.class,
                        "accessLogs",
                        new Class<?>[] {Long.class, int.class, int.class},
                        "查阅台账 GET /privacy-access-logs"));
    }

    @ParameterizedTest(name = "{3}")
    @MethodSource("sensitiveQueryEndpoints")
    @DisplayName("患者读面 GET 端点必须挂 SENSITIVE_QUERY 审计注解（W-47 等保留痕红线面冻结）")
    void sensitiveQueryAnnotationIsPresentAndExact(
            Class<?> controller, String methodName, Class<?>[] parameterTypes, String endpoint)
            throws NoSuchMethodException {
        Method method = controller.getMethod(methodName, parameterTypes);
        // 断言一：注解在位（误删即红——等保敏感查询留痕静默失守无编译期信号）
        assertThat(method.isAnnotationPresent(AuditLog.class))
                .as("%s#%s（%s）必须挂 @AuditLog 审计注解", controller.getSimpleName(), methodName, endpoint)
                .isTrue();
        // 断言二：动作类型精确匹配 SENSITIVE_QUERY（WRITE/LOGIN 等他类挂错同判红）
        assertThat(method.getAnnotation(AuditLog.class).actionType())
                .as("%s#%s（%s）审计动作类型必须精确为 SENSITIVE_QUERY", controller.getSimpleName(), methodName, endpoint)
                .isEqualTo(AuditActionType.SENSITIVE_QUERY);
    }
}
