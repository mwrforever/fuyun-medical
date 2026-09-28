package com.fuyun.iot.registry.simulated;

import com.fuyun.iot.registry.CommandRef;
import com.fuyun.iot.registry.DeviceCredential;
import com.fuyun.iot.registry.DeviceShadow;
import com.fuyun.iot.registry.IotDeviceRegistry;
import com.fuyun.iot.registry.ProductRef;
import com.fuyun.iot.registry.ProductSpec;
import com.fuyun.iot.registry.RegistryDeviceSpec;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 模拟注册中心实现（P2 PR-2 Task 4）：内存 ConcurrentHashMap 形态的 IotDeviceRegistry——
 * dev/test/CI 无云依赖运行面（fuyun.iot.admin.enabled 默认 false 下的唯一装配实现），行为语义
 * 与华为实现等价：secret 生成=UUID、影子=本地 map、命令=即时回执 SUCCESS。
 *
 * <p>线程安全说明：产品/设备/影子三面状态均落 ConcurrentHashMap，注册/删除动作幂等（重复删除
 * 不抛）；本实现仅服务本地联调与自动化测试，禁在生产 profile 装配（双条件装配红线，
 * IotRegistryConfig 默认面）。凭证红线：secret 仅随返回值一次性透出，日志只记 deviceId 与
 * credentialRef。
 */
@Slf4j
public class SimulatedRegistry implements IotDeviceRegistry {

    /** 模拟产品标识前缀（与真实 IoTDA productId 命名空间显式区隔，排障一眼可辨） */
    private static final String SIM_PRODUCT_PREFIX = "SIM-";

    /** 模拟命令标识前缀 */
    private static final String SIM_COMMAND_PREFIX = "SIM-CMD-";

    /** 模拟凭证引用前缀（落库 credential_ref 形态；不含 secret 明文） */
    private static final String SIM_CREDENTIAL_PREFIX = "sim-cred-";

    /** 模拟命令即时回执结果（行为等价契约：命令=即时回执 SUCCESS） */
    private static final String COMMAND_SUCCESS = "SUCCESS";

    /** 产品状态：模拟产品表（productId → 物模型 JSON 快照），并发面加锁容器 */
    private final ConcurrentHashMap<String, String> products = new ConcurrentHashMap<>();

    /** 设备状态：模拟设备表（deviceId → 一机一密凭证明文，仅内存持有供换发比对） */
    private final ConcurrentHashMap<String, String> deviceSecrets = new ConcurrentHashMap<>();

    /** 构造器（装配归 IotRegistryConfig 双条件默认面；无外部依赖） */
    public SimulatedRegistry() {}

    @Override
    public ProductRef createProduct(ProductSpec spec) {
        // 模拟产品标识由 UUID 生成（与真实 IoTDA 分配语义等价：本地禁生成自然键的例外仅模拟器）
        String productId = SIM_PRODUCT_PREFIX + UUID.randomUUID();
        if (spec.modelDefinitionJson() != null && !spec.modelDefinitionJson().isBlank()) {
            products.put(productId, spec.modelDefinitionJson());
        }
        log.info("模拟注册中心产品创建：productId={}，productName={}", productId, spec.productName());
        return new ProductRef(productId);
    }

    @Override
    public void syncModel(String productId, String modelDefinitionJson) {
        // 幂等覆盖：模型快照以最近一次同步为权威（模拟器无云端校验面）
        products.put(productId, modelDefinitionJson);
        log.info("模拟注册中心物模型同步：productId={}", productId);
    }

    @Override
    public DeviceCredential registerDevice(RegistryDeviceSpec spec) {
        // 一机一密生成契约：UUID 形态明文，仅随返回值一次性透出（禁日志，14-iot §9）
        String secret = UUID.randomUUID().toString();
        deviceSecrets.put(spec.deviceId(), secret);
        String credentialRef = SIM_CREDENTIAL_PREFIX + UUID.randomUUID();
        log.info("模拟注册中心设备注册：deviceId={}，credentialRef={}", spec.deviceId(), credentialRef);
        return new DeviceCredential(credentialRef, secret);
    }

    @Override
    public DeviceCredential resetDeviceCredential(String deviceId) {
        // 换发即覆盖：全新 UUID secret 与全新凭证引用（本地 credential_ref 随返回值轮换），
        // secret 仅随返回值一次性透出，不落日志（14-iot §9 红线）
        String secret = UUID.randomUUID().toString();
        String credentialRef = SIM_CREDENTIAL_PREFIX + UUID.randomUUID();
        deviceSecrets.put(deviceId, secret);
        log.info("模拟注册中心凭证换发：deviceId={}，credentialRef={}", deviceId, credentialRef);
        return new DeviceCredential(credentialRef, secret);
    }

    @Override
    public CommandRef sendCommand(String deviceId, String commandName, Map<String, Object> params) {
        // 行为等价契约：模拟器命令即时成功（无真实设备等待面）
        String commandId = SIM_COMMAND_PREFIX + UUID.randomUUID();
        log.info("模拟注册中心命令下发：deviceId={}，commandName={}，commandId={}", deviceId, commandName, commandId);
        return new CommandRef(commandId, COMMAND_SUCCESS);
    }

    @Override
    public DeviceShadow shadow(String deviceId) {
        // 影子=本地 map（行为等价契约）：模拟器无遥测写入面，双面初态恒空但契约非 null
        return new DeviceShadow(Map.of(), Map.of());
    }

    @Override
    public void deleteProduct(String productId) {
        // 幂等删除：remove 返回 null（产品不存在）同样视为成功（模拟器宽松删除语义）
        products.remove(productId);
        log.info("模拟注册中心产品删除：productId={}", productId);
    }

    @Override
    public void deregisterDevice(String deviceId) {
        // 幂等注销：移除设备凭证（模拟器无历史留痕面）
        deviceSecrets.remove(deviceId);
        log.info("模拟注册中心设备注销：deviceId={}", deviceId);
    }
}
