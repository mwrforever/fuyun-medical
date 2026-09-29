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
import com.huaweicloud.sdk.core.exception.ClientRequestException;
import com.huaweicloud.sdk.core.http.HttpConfig;
import com.huaweicloud.sdk.core.invoker.BaseInvoker;
import com.huaweicloud.sdk.core.invoker.SyncInvoker;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p><b>外部客户端防御配置（B.4-2，EX-40）</b>：超时经 SDK 原生 {@link HttpConfig} 显式锁定
 * （连接 {@link #CONNECT_TIMEOUT_SECONDS} / 读 {@link #READ_TIMEOUT_SECONDS}，SDK 缺省 60s/120s
 * 与管理台同步人机链路失配）；重试用 SDK 原生 invoker 重试面（{@link #RETRY_TIMES} 次，仅连接级
 * 异常触发——请求未送达才对非幂等写安全，退避由 SDK 缺省节流感知策略承载）；熔断 SDK 无原生
 * 支持，本类自实现最小连续失败计数熔断（{@link #CIRCUIT_FAILURE_THRESHOLD} 次云端不可用信号
 * 开断 + {@link #CIRCUIT_OPEN_COOLDOWN_MILLIS} 冷却后放行单次半开探测，成功闭合/失败复断），
 * 三者统一收口在 {@link #invoke(SyncInvoker)} 调用通道。
 *
 * <p>凭证红线：AK/SK/设备 secret 一律禁入日志（注册凭证仅随 DeviceCredential 返回值一次性
 * 透出，响应体 secret 不二次落地）；toString 不覆写（本类不承载凭证字段，凭证仅存于
 * IotdaAdminProperties 的脱敏形态）。
 *
 * <p>线程安全说明：client 惰性字段为 volatile + synchronized 双检构建，注册中心单例下并发
 * 调用安全；client 本身线程安全（华为 SDK 契约）。熔断计数与开断时刻的复合变更在 synchronized
 * 方法内互斥完成，开断检查读 volatile 尽力而为（检查与开断交错时至多多放行一次调用，可接受）。
 */
@Slf4j
public class HuaweiIotdaRegistry implements IotDeviceRegistry {

    /** 模型 JSON ↔ SDK ServiceCapability 转换的类型锚（服务能力数组形态） */
    private static final TypeReference<List<ServiceCapability>> SERVICE_CAPABILITY_LIST = new TypeReference<>() {};

    /**
     * IoTDA 管理面连接超时（秒，B.4-2 连接超时显式配置）：TCP+TLS 握手上限。SDK 缺省 60s 对
     * 管理台同步人机链路过长（nginx 网关侧早已超时报错，后端继续等满徒增占用）；10s 覆盖公网
     * 握手正常抖动。SDK 语义：HttpConfig 单位为秒，经 TimeUnit.SECONDS 下推 okhttp connectTimeout。
     */
    static final int CONNECT_TIMEOUT_SECONDS = 10;

    /**
     * IoTDA 管理面读超时（秒，B.4-2 读超时显式配置）：云端单次 API 处理上限。SDK 缺省 120s
     * 与管理台交互容忍量级失配（后端挂满 120s 也返不回调用方）；30s 对齐管理台交互容忍并留
     * 后端处理余量。
     */
    static final int READ_TIMEOUT_SECONDS = 30;

    /**
     * SDK 原生 invoker 重试上限（B.4-2 有界重试）：SDK 重试须逐调用经 invoker 显式挂载（无
     * client 级全局配置项），{@code BaseInvoker.defaultRetryCondition()} 仅对连接级异常
     * （ConnectionException，请求未送达）重试——对 createProduct/addDevice 等非幂等写安全；
     * 退避由 SDK 缺省节流感知策略（SdkBackoffStrategy）承载。取 2 次（SDK 硬顶
     * BaseInvoker.MAX_RETRY_TIME=30 内），最坏 3 次连接尝试（3×10s）仍在读超时容忍面内。
     */
    static final int RETRY_TIMES = 2;

    /**
     * 熔断开断阈值（B.4-2 熔断降级，SDK 无原生支持自实现最小形态）：连续 5 次云端不可用信号
     * 即开断——防持续故障期管理台请求逐个挂满超时拖垮调用线程；4xx 属请求侧问题（云端健康
     * 可达）不计入，防业务性拒绝误开断。
     */
    static final int CIRCUIT_FAILURE_THRESHOLD = 5;

    /**
     * 熔断开断冷却毫秒（半开探测间隔）：开断 30s 后放行单次探测调用——成功即闭合、失败即
     * 复断，用周期性放行探测替代固定长冷却的恢复盲区（云端恢复后至多 30s 自愈）。
     */
    static final long CIRCUIT_OPEN_COOLDOWN_MILLIS = 30_000L;

    /** 管理面配置属性（v5 端点 + 凭证三要素，凭证仅经脱敏形态进入本类日志面） */
    private final IotdaAdminProperties properties;

    /** JSON 转换器（模型快照 ↔ SDK ServiceCapability；装配经 IotRegistryConfig 注入 Boot 实例） */
    private final ObjectMapper objectMapper;

    /** SDK 同步客户端：惰性构建（首次调用建连；volatile 保多线程可见性） */
    private volatile IoTDAClient client;

    /** 连续失败计数（熔断判定依据，成功清零；复合变更与开断时刻在 synchronized 方法内互斥） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** 熔断开断截止时刻（epoch 毫秒，0=闭合；volatile 保并发检查读可见，写仅 synchronized 内） */
    private volatile long circuitOpenUntilMillis;

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
            CreateProductResponse response = invoke(client().createProductInvoker(request));
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
            invoke(client().updateProductInvoker(request));
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
            String deviceId = invoke(client().addDeviceInvoker(request)).getDeviceId();
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
            invoke(client().resetDeviceSecretInvoker(request));
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
            CreateCommandResponse response = invoke(client().createCommandInvoker(request));
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
            ShowDeviceShadowResponse response = invoke(client().showDeviceShadowInvoker(request));
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
            invoke(client().deleteProductInvoker(request));
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
            invoke(client().deleteDeviceInvoker(request));
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
                        current = buildClient();
                    } catch (Exception e) {
                        log.error("IoTDA SDK 客户端构建失败：endpoint={}，原因={}", properties.endpoint(), e.getMessage(), e);
                        throw new RegistryException("IoTDA 客户端构建失败：" + e.getMessage(), e);
                    }
                    client = current;
                    log.info(
                            "IoTDA SDK 客户端构建完成：endpoint={}，connectTimeout={}s，readTimeout={}s",
                            properties.endpoint(),
                            CONNECT_TIMEOUT_SECONDS,
                            READ_TIMEOUT_SECONDS);
                }
            }
        }
        return current;
    }

    /**
     * 构建 SDK 客户端（含 B.4-2 显式超时配置面）：凭据/端点来自管理面属性，超时经
     * {@link #iotdaHttpConfig()} 显式锁定（SDK 缺省 60s/120s 与管理台同步链路失配）；构建本身
     * 不触网。包私有供单测断言客户端构造参数（client() 惰性路径归生产调用面）。
     *
     * @return SDK 客户端实例，非空
     */
    IoTDAClient buildClient() {
        return IoTDAClient.newBuilder()
                .withCredential(new BasicCredentials()
                        .withAk(properties.accessKey())
                        .withSk(properties.accessSecret())
                        .withProjectId(properties.projectId()))
                .withEndpoint(properties.endpoint())
                .withHttpConfig(iotdaHttpConfig())
                .build();
    }

    /**
     * IoTDA 管理面 HTTP 配置（B.4-2 连接/读超时显式锁定）：以 SDK 缺省配置为基底仅覆写两个
     * 超时项——其余面（SSL 校验/代理等）维持 SDK 缺省安全姿态。包私有供单测断言配置值。
     *
     * @return 超时显式化的 HTTP 配置，非空
     */
    static HttpConfig iotdaHttpConfig() {
        return HttpConfig.getDefaultHttpConfig()
                .withConnectionTimeout(CONNECT_TIMEOUT_SECONDS)
                .withReadTimeout(READ_TIMEOUT_SECONDS);
    }

    /**
     * SDK 调用统一通道（B.4-2 防御语义唯一收口）：熔断前置检查（开断中快速失败不触云端，也不
     * 计入失败——未触达云端）→ SDK 原生有界重试挂载（仅连接级异常重试，退避由 SDK 缺省节流
     * 感知策略承载）→ 成功/失败回填熔断计数。任一异常原样上抛，由各业务方法的 catch 统一转
     * {@link RegistryException}（IOT-1022 语义），本通道不改翻译语义。
     *
     * @param <R> SDK 请求类型
     * @param <S> SDK 响应类型
     * @param invoker SDK 调用器（由 client 的 xxxInvoker 工厂方法构造），非空
     * @return SDK 响应，非空
     * @throws RegistryException 熔断开断中（快速失败，IOT-1022 语义）
     * @throws RuntimeException SDK 调用失败原样上抛（由调用方统一转译）
     */
    private <R, S> S invoke(SyncInvoker<R, S> invoker) {
        // 熔断开断检查：开断中直接快速失败（开断由连续失败触发，快速失败防逐请求挂满超时）
        long now = System.currentTimeMillis();
        if (now < circuitOpenUntilMillis) {
            log.warn("IoTDA 熔断开断中，本次调用快速失败：剩余冷却毫秒={}", circuitOpenUntilMillis - now);
            throw new RegistryException("IoTDA 熔断开断中：连续失败达阈值，快速失败");
        }
        try {
            // SDK 原生有界重试（仅连接级异常触发，非幂等写安全；退避由 SDK 缺省策略承载）
            S response = invoker.withRetry(RETRY_TIMES, BaseInvoker.defaultRetryCondition())
                    .invoke();
            onCallSucceeded();
            return response;
        } catch (RuntimeException e) {
            onCallFailed(e);
            throw e;
        }
    }

    /**
     * 调用成功回填熔断状态：半开探测成功（曾开断）即整体闭合并 info 留痕；闭合态常规成功仅
     * 静默清零计数（失败计数非零或曾开断才写，常规成功零开销）。synchronized 与失败回填互斥，
     * 防计数与开断时刻交错。
     */
    private synchronized void onCallSucceeded() {
        if (consecutiveFailures.get() == 0 && circuitOpenUntilMillis == 0L) {
            return;
        }
        if (circuitOpenUntilMillis > 0L) {
            log.info("IoTDA 熔断半开探测成功，恢复闭合：endpoint={}", properties.endpoint());
        }
        consecutiveFailures.set(0);
        circuitOpenUntilMillis = 0L;
    }

    /**
     * 调用失败回填熔断状态：仅云端不可用信号计入连续失败（连接级/超时/5xx 与未知运行时异常）；
     * 4xx（{@link ClientRequestException}）属请求侧问题、云端健康可达，不计入——防业务性拒绝
     * （如删除不存在产品）连击误开断。连续达阈值即开断并 warn 留痕。
     *
     * @param failure SDK 调用抛出的运行时异常，非空
     */
    private synchronized void onCallFailed(RuntimeException failure) {
        if (failure instanceof ClientRequestException) {
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= CIRCUIT_FAILURE_THRESHOLD) {
            // 开断：冷却窗口内全部调用快速失败，到期后放行单次半开探测
            circuitOpenUntilMillis = System.currentTimeMillis() + CIRCUIT_OPEN_COOLDOWN_MILLIS;
            log.warn(
                    "IoTDA 熔断开断：连续失败 {} 次达阈值，冷却毫秒={}，原因摘要={}",
                    failures,
                    CIRCUIT_OPEN_COOLDOWN_MILLIS,
                    failure.getMessage());
        }
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
