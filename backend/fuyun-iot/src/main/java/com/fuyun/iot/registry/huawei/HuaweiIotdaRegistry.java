package com.fuyun.iot.registry.huawei;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.properties.IotdaAdminProperties;
import com.fuyun.iot.registry.CommandRef;
import com.fuyun.iot.registry.DeviceCredential;
import com.fuyun.iot.registry.DeviceShadow;
import com.fuyun.iot.registry.IotDeviceRegistry;
import com.fuyun.iot.registry.ProductRef;
import com.fuyun.iot.registry.ProductSpec;
import com.fuyun.iot.registry.RegistryDeviceSpec;
import com.fuyun.iot.registry.RegistryException;
import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.iotda.v5.IoTDAClient;
import com.huaweicloud.sdk.iotda.v5.model.AddDevice;
import com.huaweicloud.sdk.iotda.v5.model.AddDeviceRequest;
import com.huaweicloud.sdk.iotda.v5.model.AddProduct;
import com.huaweicloud.sdk.iotda.v5.model.AuthInfo;
import com.huaweicloud.sdk.iotda.v5.model.CreateCommandRequest;
import com.huaweicloud.sdk.iotda.v5.model.CreateCommandResponse;
import com.huaweicloud.sdk.iotda.v5.model.CreateProductRequest;
import com.huaweicloud.sdk.iotda.v5.model.CreateProductResponse;
import com.huaweicloud.sdk.iotda.v5.model.DeleteDeviceRequest;
import com.huaweicloud.sdk.iotda.v5.model.DeleteProductRequest;
import com.huaweicloud.sdk.iotda.v5.model.DeviceCommandRequest;
import com.huaweicloud.sdk.iotda.v5.model.DeviceShadowData;
import com.huaweicloud.sdk.iotda.v5.model.ResetDeviceSecret;
import com.huaweicloud.sdk.iotda.v5.model.ResetDeviceSecretRequest;
import com.huaweicloud.sdk.iotda.v5.model.ServiceCapability;
import com.huaweicloud.sdk.iotda.v5.model.ShowDeviceShadowRequest;
import com.huaweicloud.sdk.iotda.v5.model.ShowDeviceShadowResponse;
import com.huaweicloud.sdk.iotda.v5.model.UpdateProduct;
import com.huaweicloud.sdk.iotda.v5.model.UpdateProductRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * 华为云 IoTDA 注册中心实现（P2 PR-2 Task 4）：IotDeviceRegistry 的生产实现，v5 端点 +
 * BasicCredentials(AK/SK/projectId) 惰性构建 SDK client（首次调用才建，enabled=true 装配不触网）；
 * 全部方法 try-catch 兜底转 {@link RegistryException}（IOT-1022 语义）。
 *
 * <p>SDK 方法映射表（3.1.218 实测定稿，brief 预期偏差已申报）：createProduct→createProduct；
 * syncModel→updateProduct（SDK 无独立模型上传端点，模型经 serviceCapabilities 随产品面提交）；
 * registerDevice→addDevice（SDK 无 createDevice 命名）；resetDeviceCredential→resetDeviceSecret；
 * sendCommand→createCommand（同步命令面）；shadow→showDeviceShadow；deleteProduct→deleteProduct；
 * deregisterDevice→deleteDevice（SDK 注销设备命名）。
 *
 * <p>凭证红线：AK/SK/设备 secret 一律禁入日志（注册凭证仅随 DeviceCredential 返回值一次性
 * 透出，响应体 secret 不二次落地）；toString 不覆写（本类不承载凭证字段，凭证仅存于
 * IotdaAdminProperties 的脱敏形态）。
 *
 * <p>线程安全说明：client 惰性字段为 volatile + synchronized 双检构建，注册中心单例下并发
 * 调用安全；client 本身线程安全（华为 SDK 契约）。
 */
@Slf4j
public class HuaweiIotdaRegistry implements IotDeviceRegistry {

    /** 模型 JSON ↔ SDK ServiceCapability 转换的类型锚（服务能力数组形态） */
    private static final TypeReference<List<ServiceCapability>> SERVICE_CAPABILITY_LIST = new TypeReference<>() {};

    /** 管理面配置属性（v5 端点 + 凭证三要素，凭证仅经脱敏形态进入本类日志面） */
    private final IotdaAdminProperties properties;

    /** JSON 转换器（模型快照 ↔ SDK ServiceCapability；装配经 IotRegistryConfig 注入 Boot 实例） */
    private final ObjectMapper objectMapper;

    /** SDK 同步客户端：惰性构建（首次调用建连；volatile 保多线程可见性） */
    private volatile IoTDAClient client;

    /**
     * 全参构造器（装配归 IotRegistryConfig Huawei 条件面）。
     *
     * @param properties   管理面配置属性，非空；构建前已过 validateAdminEnabled fail-fast
     * @param objectMapper JSON 转换器，非空；来源：Boot 容器 ObjectMapper
     */
    public HuaweiIotdaRegistry(IotdaAdminProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 产品创建的 IoTDA 侧执行体（映射 SDK createProduct）：物模型随产品创建一并提交（SDK 无
     * 独立模型上传端点，serviceCapabilities 随产品面承载）；manufacturerName/industry/
     * description 可选段仅在场时携带。受理成功返回云端分配的 productId（调用方随后落镜像行），
     * 任一异常（网络/凭证/云端拒绝）统一转 {@link RegistryException} 交服务层渲染 IOT-1022。
     *
     * @param spec 产品规格（名称/类型/协议/数据格式与可选模型 JSON），非空；来源：产品服务上架编排
     * @return 云端产品引用（productId 为 IoTDA 分配值），非空
     * @throws RegistryException IoTDA 调用失败（端点不可达/凭证非法/参数被云端拒绝，IOT-1022 语义），
     *                           建议调用方转 BizException 渲染管理台提示
     */
    @Override
    public ProductRef createProduct(ProductSpec spec) {
        try {
            AddProduct body = new AddProduct()
                    .withName(spec.productName())
                    .withDeviceType(spec.deviceType())
                    .withProtocolType(spec.protocolType())
                    .withDataFormat(spec.dataFormat());
            if (spec.manufacturerName() != null) {
                body.setManufacturerName(spec.manufacturerName());
            }
            if (spec.industry() != null) {
                body.setIndustry(spec.industry());
            }
            if (spec.description() != null) {
                body.setDescription(spec.description());
            }
            // 物模型随产品创建一并提交（SDK 无独立模型上传端点，3.1.218 实测）
            if (spec.modelDefinitionJson() != null
                    && !spec.modelDefinitionJson().isBlank()) {
                body.setServiceCapabilities(parseServiceCapabilities(spec.modelDefinitionJson()));
            }
            CreateProductRequest request = new CreateProductRequest();
            request.setBody(body);
            CreateProductResponse response = client().createProduct(request);
            log.info("IoTDA 产品创建成功：productId={}，productName={}", response.getProductId(), spec.productName());
            return new ProductRef(response.getProductId());
        } catch (Exception e) {
            log.error("IoTDA 产品创建失败：productName={}，原因={}", spec.productName(), e.getMessage(), e);
            throw new RegistryException("IoTDA 产品创建失败：" + e.getMessage(), e);
        }
    }

    /**
     * 物模型同步的 IoTDA 侧执行体（映射 SDK updateProduct）：serviceCapabilities 全量重推（本地
     * 快照为权威源），云端受理即返回，一致性对账由调用方按映射表完成；模型 JSON 解析失败按快照
     * 数据损坏处置，同样转 {@link RegistryException} 上抛。
     *
     * @param productId           IoTDA 产品标识，非空；来源：iot_product.product_id 镜像行
     * @param modelDefinitionJson 模型 JSON 快照（服务能力数组形态），非空；来源：iot_product.model_definition
     * @throws RegistryException IoTDA 调用失败或模型 JSON 非法（IOT-1022 语义），建议调用方对账告警
     */
    @Override
    public void syncModel(String productId, String modelDefinitionJson) {
        try {
            // 模型同步映射 updateProduct：serviceCapabilities 全量重推（本地快照为权威）
            UpdateProduct body =
                    new UpdateProduct().withServiceCapabilities(parseServiceCapabilities(modelDefinitionJson));
            UpdateProductRequest request =
                    new UpdateProductRequest().withProductId(productId).withBody(body);
            client().updateProduct(request);
            log.info("IoTDA 物模型同步成功：productId={}", productId);
        } catch (Exception e) {
            log.error("IoTDA 物模型同步失败：productId={}，原因={}", productId, e.getMessage(), e);
            throw new RegistryException("IoTDA 物模型同步失败：" + e.getMessage(), e);
        }
    }

    /**
     * 设备注册的 IoTDA 侧执行体（映射 SDK addDevice）：本地生成 UUID secret 经 authInfo 传入
     * 云端（一机一密），credentialRef 固定 "iotda-{deviceId}" 形态；secret 仅随返回值一次性
     * 透出，禁日志禁二次落地（14-iot §9 红线）。
     *
     * @param spec 设备规格（deviceId/nodeId/productId/deviceName），非空；来源：设备接入登记
     * @return 设备凭证（credentialRef 引用 + secret 明文一次性面），非空
     * @throws RegistryException IoTDA 调用失败（设备已存在/参数被拒/端点不可达，IOT-1022 语义）
     */
    @Override
    public DeviceCredential registerDevice(RegistryDeviceSpec spec) {
        try {
            // 一机一密：本地生成 UUID secret 经 authInfo 传入云端（与模拟实现生成契约一致）；
            // secret 只随返回值一次性透出，禁落日志禁二次落地（14-iot §9）
            String secret = UUID.randomUUID().toString();
            AuthInfo authInfo = new AuthInfo().withSecret(secret).withSecureAccess(true);
            AddDevice body = new AddDevice()
                    .withDeviceId(spec.deviceId())
                    .withNodeId(spec.nodeId())
                    .withProductId(spec.productId())
                    .withDeviceName(spec.deviceName())
                    .withAuthInfo(authInfo);
            AddDeviceRequest request = new AddDeviceRequest();
            request.setBody(body);
            String deviceId = client().addDevice(request).getDeviceId();
            String credentialRef = "iotda-" + deviceId;
            log.info("IoTDA 设备注册成功：deviceId={}，credentialRef={}", deviceId, credentialRef);
            return new DeviceCredential(credentialRef, secret);
        } catch (Exception e) {
            log.error("IoTDA 设备注册失败：deviceId={}，原因={}", spec.deviceId(), e.getMessage(), e);
            throw new RegistryException("IoTDA 设备注册失败：" + e.getMessage(), e);
        }
    }

    /**
     * 凭证换发的 IoTDA 侧执行体（映射 SDK resetDeviceSecret）：换发即覆盖——本地生成新 UUID
     * secret 传云端（IoTDA 重置为入参式换发，非云端随机生成），旧 secret 即刻失效；新 secret
     * 仅随返回值一次性透出（与 registerDevice 同红线：禁日志禁落库，云端响应体不读取）。
     *
     * @param deviceId IoTDA 设备标识，非空；来源：iot_device.device_id
     * @return 换发后设备凭证（credentialRef 引用 + 新 secret 明文一次性面），非空
     * @throws RegistryException IoTDA 调用失败或设备不存在于云端（IOT-1022 语义）
     */
    @Override
    public DeviceCredential resetDeviceCredential(String deviceId) {
        try {
            // 换发即覆盖：本地生成新 secret 传云端（IoTDA 重置密钥为入参式换发），secret 仅随
            // 返回值一次性透出（与 registerDevice 同红线：禁日志禁落库，云端响应体不读取）
            String secret = UUID.randomUUID().toString();
            ResetDeviceSecret body = new ResetDeviceSecret().withSecret(secret);
            ResetDeviceSecretRequest request = new ResetDeviceSecretRequest().withDeviceId(deviceId);
            request.setBody(body);
            client().resetDeviceSecret(request);
            String credentialRef = "iotda-" + deviceId;
            log.info("IoTDA 设备凭证换发成功：deviceId={}，credentialRef={}", deviceId, credentialRef);
            return new DeviceCredential(credentialRef, secret);
        } catch (Exception e) {
            log.error("IoTDA 设备凭证换发失败：deviceId={}，原因={}", deviceId, e.getMessage(), e);
            throw new RegistryException("IoTDA 设备凭证换发失败：" + e.getMessage(), e);
        }
    }

    /**
     * 命令下发的 IoTDA 侧执行体（映射 SDK createCommand 同步命令面）：空参命令不携带 paras 段；
     * 云端 response 体（可空，设备回执摘要）转文本随 {@link CommandRef} 供管理台回显。
     *
     * @param deviceId    IoTDA 设备标识，非空；来源：iot_device.device_id
     * @param commandName 命令名称（物模型 commands[].name），非空；来源：产品命令定义
     * @param params      命令参数键值对，可空（无参命令传 null 或空 map）
     * @return 命令回执引用（commandId + 结果摘要文本，摘要可空），非空
     * @throws RegistryException IoTDA 调用失败/设备离线/命令被云端拒绝（IOT-1022 语义）
     */
    @Override
    public CommandRef sendCommand(String deviceId, String commandName, Map<String, Object> params) {
        try {
            DeviceCommandRequest body = new DeviceCommandRequest().withCommandName(commandName);
            if (params != null && !params.isEmpty()) {
                body.setParas(params);
            }
            CreateCommandRequest request = new CreateCommandRequest().withDeviceId(deviceId);
            request.setBody(body);
            CreateCommandResponse response = client().createCommand(request);
            // 同步命令结果摘要：云端 response 体（可空），转文本供管理台回显
            String result = response.getResponse() == null ? null : String.valueOf(response.getResponse());
            log.info(
                    "IoTDA 命令下发成功：deviceId={}，commandName={}，commandId={}",
                    deviceId,
                    commandName,
                    response.getCommandId());
            return new CommandRef(response.getCommandId(), result);
        } catch (Exception e) {
            log.error("IoTDA 命令下发失败：deviceId={}，commandName={}，原因={}", deviceId, commandName, e.getMessage(), e);
            throw new RegistryException("IoTDA 命令下发失败：" + e.getMessage(), e);
        }
    }

    /**
     * 影子查询的 IoTDA 侧执行体（映射 SDK showDeviceShadow）：多服务影子合并——各服务
     * desired/reported 属性键值经 Jackson 归一后并入统一 map（非对象形态属性跳过，不中断合并）。
     *
     * @param deviceId IoTDA 设备标识，非空；来源：iot_device.device_id
     * @return 设备影子（desired/reported 双面 map；设备无上报时为空 map，契约非 null），非空
     * @throws RegistryException IoTDA 调用失败或设备不存在（IOT-1022 语义）
     */
    @Override
    public DeviceShadow shadow(String deviceId) {
        try {
            ShowDeviceShadowRequest request = new ShowDeviceShadowRequest().withDeviceId(deviceId);
            ShowDeviceShadowResponse response = client().showDeviceShadow(request);
            // 多服务影子合并：各服务 desired/reported 属性键值并入统一 map（键为属性名）
            Map<String, Object> desired = new HashMap<>();
            Map<String, Object> reported = new HashMap<>();
            List<DeviceShadowData> shadowData = response.getShadow();
            if (shadowData != null) {
                for (DeviceShadowData data : shadowData) {
                    mergeShadowProperties(desired, data.getDesired());
                    mergeShadowProperties(reported, data.getReported());
                }
            }
            log.info(
                    "IoTDA 影子查询成功：deviceId={}，desiredSize={}，reportedSize={}",
                    deviceId,
                    desired.size(),
                    reported.size());
            return new DeviceShadow(desired, reported);
        } catch (Exception e) {
            log.error("IoTDA 影子查询失败：deviceId={}，原因={}", deviceId, e.getMessage(), e);
            throw new RegistryException("IoTDA 影子查询失败：" + e.getMessage(), e);
        }
    }

    /**
     * 产品删除的 IoTDA 侧执行体（映射 SDK deleteProduct）：本地镜像行由调用方处置；仍有设备
     * 挂载时云端拒绝（调用方应先清空设备再下架）。
     *
     * @param productId IoTDA 产品标识，非空；来源：iot_product.product_id 镜像行
     * @throws RegistryException IoTDA 调用失败或云端拒绝删除（设备未清空等，IOT-1022 语义）
     */
    @Override
    public void deleteProduct(String productId) {
        try {
            DeleteProductRequest request = new DeleteProductRequest().withProductId(productId);
            client().deleteProduct(request);
            log.info("IoTDA 产品删除成功：productId={}", productId);
        } catch (Exception e) {
            log.error("IoTDA 产品删除失败：productId={}，原因={}", productId, e.getMessage(), e);
            throw new RegistryException("IoTDA 产品删除失败：" + e.getMessage(), e);
        }
    }

    /**
     * 设备注销的 IoTDA 侧执行体（映射 SDK deleteDevice——SDK 无 deregisterDevice 命名，映射
     * 偏差已申报）：云端删除设备档案，凭证一并失效；本地行由调用方处置。
     *
     * @param deviceId IoTDA 设备标识，非空；来源：iot_device.device_id
     * @throws RegistryException IoTDA 调用失败（IOT-1022 语义）
     */
    @Override
    public void deregisterDevice(String deviceId) {
        try {
            // SDK 注销设备命名实测为 deleteDevice（无 deregisterDevice 方法，映射偏差已申报）
            DeleteDeviceRequest request = new DeleteDeviceRequest().withDeviceId(deviceId);
            client().deleteDevice(request);
            log.info("IoTDA 设备注销成功：deviceId={}", deviceId);
        } catch (Exception e) {
            log.error("IoTDA 设备注销失败：deviceId={}，原因={}", deviceId, e.getMessage(), e);
            throw new RegistryException("IoTDA 设备注销失败：" + e.getMessage(), e);
        }
    }

    /**
     * 取 SDK 同步客户端（惰性双检构建）：首次调用才经 v5 端点与 BasicCredentials 构建——装配期
     * 零网络请求（enabled=true 建 Bean 不触网，连接失败延迟到首次管理台调用显式暴露）。
     *
     * @return SDK 客户端单例，非空
     * @throws RegistryException 客户端构建失败（端点/凭证非法，IOT-1022 语义）
     */
    private IoTDAClient client() {
        IoTDAClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    try {
                        current = IoTDAClient.newBuilder()
                                .withCredential(new BasicCredentials()
                                        .withAk(properties.accessKey())
                                        .withSk(properties.accessSecret())
                                        .withProjectId(properties.projectId()))
                                .withEndpoint(properties.endpoint())
                                .build();
                    } catch (Exception e) {
                        log.error("IoTDA SDK 客户端构建失败：endpoint={}，原因={}", properties.endpoint(), e.getMessage(), e);
                        throw new RegistryException("IoTDA 客户端构建失败：" + e.getMessage(), e);
                    }
                    client = current;
                    log.info("IoTDA SDK 客户端构建完成：endpoint={}", properties.endpoint());
                }
            }
        }
        return current;
    }

    /**
     * 解析物模型 JSON 为 SDK 服务能力清单：本地快照（服务能力数组形态）与 SDK
     * ServiceCapability 同构，经 Jackson 反序列化直转（解析失败属快照数据损坏，按注册中心
     * 不可用上抛由调用方对账告警）。
     *
     * @param modelDefinitionJson 模型 JSON 快照，非空
     * @return 服务能力清单，非空
     * @throws RegistryException 快照非合法 JSON（IOT-1022 语义）
     */
    private List<ServiceCapability> parseServiceCapabilities(String modelDefinitionJson) {
        try {
            return objectMapper.readValue(modelDefinitionJson, SERVICE_CAPABILITY_LIST);
        } catch (Exception e) {
            log.error("物模型 JSON 解析失败：原因={}", e.getMessage(), e);
            throw new RegistryException("物模型 JSON 解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 影子单服务属性并入统一 map：SDK 影子属性为 Object 形态（属性键值对象），经 Jackson
     * 归一为 Map（非对象形态置 null 跳过，不中断多服务合并）。
     *
     * @param target     合并目标 map（desired 或 reported 面），非空
     * @param properties SDK 影子属性对象（可空）
     */
    private void mergeShadowProperties(Map<String, Object> target, Object properties) {
        if (properties == null) {
            return;
        }
        Map<String, Object> converted =
                objectMapper.convertValue(properties, new TypeReference<Map<String, Object>>() {});
        if (converted != null) {
            target.putAll(converted);
        }
    }
}
