package com.fuyun.iot.registry.simulated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fuyun.iot.registry.CommandRef;
import com.fuyun.iot.registry.DeviceShadow;
import com.fuyun.iot.registry.ProductRef;
import com.fuyun.iot.registry.ProductSpec;
import com.fuyun.iot.registry.RegistryDeviceSpec;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模拟注册中心行为等价单测（P2 PR-2 Task 4 Step 2，七用例）：dev/test/CI 无云依赖形态与
 * HuaweiIotdaRegistry 行为语义逐项对齐——产品创建回执、物模型同步幂等、一机一密注册
 * （secret=UUID 生成、credentialRef 不含 secret 明文的脱敏红线）、凭证换发、命令即时回执
 * SUCCESS、影子双面结构、删除/注销幂等。
 */
class SimulatedRegistryTest {

    /** UUID 形态正则（secret 生成契约的物理判据） */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final String DEVICE_ID = "sim-device-001";

    private SimulatedRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimulatedRegistry();
    }

    @Test
    @DisplayName("createProduct 返回非空 SIM 前缀 productId，且重复创建互不相同")
    void createProductReturnsUniqueSimProductId() {
        ProductRef first = registry.createProduct(productSpec());
        ProductRef second = registry.createProduct(productSpec());

        assertThat(first.productId()).as("模拟产品标识非空").isNotBlank().startsWith("SIM-");
        assertThat(second.productId()).as("重复创建生成独立产品标识").isNotEqualTo(first.productId());
    }

    @Test
    @DisplayName("syncModel 可重复调用幂等不抛（模拟器无云端模型校验，本地内存即权威）")
    void syncModelIsIdempotent() {
        assertThatCode(() -> {
                    registry.syncModel("SIM-1", modelJson());
                    registry.syncModel("SIM-1", modelJson());
                })
                .as("重复同步同一模型不抛异常")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("registerDevice 生成 UUID secret 且 credentialRef 不含 secret 明文（脱敏红线）")
    void registerDeviceReturnsUuidSecretAndSanitizedCredentialRef() {
        var credential = registry.registerDevice(deviceSpec());

        assertThat(credential.secret()).as("secret 为 UUID 形态（一机一密生成契约）").matches(UUID_PATTERN.pattern());
        assertThat(credential.credentialRef())
                .as("凭证引用非空且不含 secret 明文（落库仅 credential_ref 红线）")
                .isNotBlank()
                .doesNotContain(credential.secret());
    }

    @Test
    @DisplayName("resetDeviceCredential 换发返回全新凭证（UUID secret + 不含明文引用），再注册 secret 仍全新")
    void resetCredentialReturnsFreshCredentialThenReRegisterYieldsFreshSecret() {
        var first = registry.registerDevice(deviceSpec());

        var rotated = registry.resetDeviceCredential(DEVICE_ID);

        assertThat(rotated.secret())
                .as("换发返回新 secret 为 UUID 形态（一次性透出契约，禁日志禁落库）")
                .isNotEqualTo(first.secret())
                .matches(UUID_PATTERN.pattern());
        assertThat(rotated.credentialRef())
                .as("换发凭证引用非空且不含 secret 明文（本地 credential_ref 轮换数据源）")
                .isNotBlank()
                .doesNotContain(rotated.secret());

        var second = registry.registerDevice(deviceSpec());
        assertThat(second.secret())
                .as("换发后再注册 secret 为全新 UUID（与换发前后均不同，UUID 唯一性）")
                .isNotEqualTo(first.secret())
                .isNotEqualTo(rotated.secret())
                .matches(UUID_PATTERN.pattern());
    }

    @Test
    @DisplayName("sendCommand 即时回执 SUCCESS（commandId 非空 + result=SUCCESS）")
    void sendCommandReturnsImmediateSuccess() {
        CommandRef ref = registry.sendCommand(DEVICE_ID, "setWorkMode", Map.of("mode", "sleep"));

        assertThat(ref.commandId()).as("模拟命令回执标识非空").isNotBlank();
        assertThat(ref.result()).as("模拟命令即时成功回执").isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("shadow 返回非 null 双面结构（desired/reported 初态为空 map）")
    void shadowReturnsBothFaces() {
        DeviceShadow shadow = registry.shadow(DEVICE_ID);

        assertThat(shadow).as("影子查询契约双面结构").isNotNull();
        assertThat(shadow.desired()).as("期望面初态为空 map").isNotNull().isEmpty();
        assertThat(shadow.reported()).as("上报面初态为空 map").isNotNull().isEmpty();
    }

    @Test
    @DisplayName("deleteProduct/deregisterDevice 重复调用幂等不抛（模拟器宽松删除语义）")
    void deleteOperationsAreIdempotent() {
        ProductRef product = registry.createProduct(productSpec());
        registry.registerDevice(deviceSpec());

        assertThatCode(() -> {
                    registry.deleteProduct(product.productId());
                    registry.deleteProduct(product.productId());
                    registry.deregisterDevice(DEVICE_ID);
                    registry.deregisterDevice(DEVICE_ID);
                })
                .as("重复删除/注销不抛异常")
                .doesNotThrowAnyException();
    }

    /** 产品规格夹具（deviceType/protocol 与 V400 设备夹具同族） */
    private static ProductSpec productSpec() {
        return new ProductSpec("多参数监护仪", "MONITOR", "MQTT", "JSON", "模拟厂商", "医疗设备", "测试用产品", modelJson());
    }

    /** 设备规格夹具 */
    private static RegistryDeviceSpec deviceSpec() {
        return new RegistryDeviceSpec(DEVICE_ID, "node-001", "SIM-PRODUCT", "演示监护仪");
    }

    /** 物模型 JSON 夹具（服务能力数组形态，ServiceCapability 同构） */
    private static String modelJson() {
        return "{\"services\":[{\"serviceId\":\"vital\",\"properties\":[{\"name\":\"heartRate\"}]}]}";
    }
}
