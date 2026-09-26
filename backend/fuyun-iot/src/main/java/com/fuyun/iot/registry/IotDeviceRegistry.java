package com.fuyun.iot.registry;

import java.util.Map;

/**
 * IoT 设备注册中心进程内接口（P2 PR-2 Task 4，管理台调用方唯一出口）：屏蔽华为云 IoTDA 与
 * 本地模拟双实现的端点差异。产品/物模型/设备/命令/影子五面管理动作均经本接口发起——调用方
 * （产品服务）禁止直连 SDK 或感知 RegistryException 以外的实现细节；SDK 层任何异常由实现统一
 * 转 {@link RegistryException}（IOT-1022 注册中心不可用语义），服务层转 BizException 渲染。
 *
 * <p>凭证红线（14-iot §9）：{@link #registerDevice(RegistryDeviceSpec)} 返回的一机一密 secret
 * 只在响应出现一次，落库仅 credential_ref，任何实现禁止将 secret 写入日志或 toString；
 * {@link #resetDeviceCredential(String)} 换发的新 secret 同样只随云端生效不落地。
 *
 * <p>双实现装配（IotRegistryConfig）：fuyun.iot.admin.enabled=true → 华为云实现（HuaweiIotdaRegistry）；
 * 默认 false → 模拟实现（SimulatedRegistry，dev/test/CI 无云依赖）。行为语义等价契约：
 * secret 生成=UUID、影子=本地 map、命令=即时回执 SUCCESS。
 */
public interface IotDeviceRegistry {

    /**
     * 创建产品（上架第一环）：注册中心受理后返回其分配的产品标识，调用方随后本地落镜像行
     * （sync_status=SYNCING）。
     *
     * @param spec 产品规格（名称/设备类型/协议/数据格式与可选物模型 JSON），非空；来源：管理台上架表单
     * @return 注册中心产品引用（productId 非空），非空
     * @throws RegistryException 注册中心不可达或受理失败（IOT-1022 语义，网络/权限/参数被云端拒绝）
     */
    ProductRef createProduct(ProductSpec spec);

    /**
     * 物模型同步（上架流水线第二环）：把本地模型 JSON 快照重推注册中心（对账/变更即对齐，
     * 14-iot FU-M14-02）。云端受理即返回，一致性对账（失配检测）由调用方按映射表完成。
     *
     * @param productId           注册中心产品标识，非空；来源：iot_product.product_id
     * @param modelDefinitionJson 模型 JSON 快照（服务能力数组形态），非空；来源：iot_product.model_definition
     * @throws RegistryException 注册中心不可达或模型被云端拒绝（IOT-1022 语义）
     */
    void syncModel(String productId, String modelDefinitionJson);

    /**
     * 注册设备（一机一密）：注册中心生成设备凭证，secret 仅在本返回值一次性透出，落库仅
     * credential_ref（14-iot §9 红线——调用方禁落库禁日志 secret）。
     *
     * @param spec 设备规格（deviceId/nodeId/productId/deviceName），非空；来源：设备接入登记
     * @return 设备凭证（credentialRef 引用 + secret 明文一次性面），非空
     * @throws RegistryException 注册中心不可达或设备被拒（IOT-1022 语义）
     */
    DeviceCredential registerDevice(RegistryDeviceSpec spec);

    /**
     * 换发设备凭证（凭证泄露/周期轮换场景）：注册中心侧换发新 secret，本地无需变更
     * （credential_ref 引用不变）。
     *
     * @param deviceId 注册中心设备标识，非空；来源：iot_device.device_id
     * @throws RegistryException 注册中心不可达或设备不存在于注册中心（IOT-1022 语义）
     */
    void resetDeviceCredential(String deviceId);

    /**
     * 下发设备命令（同步命令面）：注册中心受理并等待设备回执。
     *
     * @param deviceId    注册中心设备标识，非空
     * @param commandName 命令名称（物模型 commands[].name），非空
     * @param params      命令参数键值对，可空（无参命令传 null 或空 map）
     * @return 命令回执引用（commandId + 结果摘要），非空
     * @throws RegistryException 注册中心不可达/设备离线/命令被云端拒绝（IOT-1022 语义）
     */
    CommandRef sendCommand(String deviceId, String commandName, Map<String, Object> params);

    /**
     * 查询设备影子（desired/reported 双面）：期望态与上报态的属性快照，供管理台核对设备侧
     * 配置与实际状态差异。
     *
     * @param deviceId 注册中心设备标识，非空
     * @return 设备影子（两面均非 null，初态可为空 map），非空
     * @throws RegistryException 注册中心不可达或设备不存在（IOT-1022 语义）
     */
    DeviceShadow shadow(String deviceId);

    /**
     * 删除产品（下架第一环）：注册中心侧删除（有设备挂载时云端拒绝），本地镜像行由调用方
     * 处置。幂等：已删除产品重复删除不抛（云端 404 归一为成功）。
     *
     * @param productId 注册中心产品标识，非空
     * @throws RegistryException 注册中心不可达或云端拒绝（设备未清空等，IOT-1022 语义）
     */
    void deleteProduct(String productId);

    /**
     * 注销设备：注册中心侧删除设备档案（凭证一并失效）。幂等：已注销设备重复注销不抛。
     *
     * @param deviceId 注册中心设备标识，非空
     * @throws RegistryException 注册中心不可达（IOT-1022 语义）
     */
    void deregisterDevice(String deviceId);
}
