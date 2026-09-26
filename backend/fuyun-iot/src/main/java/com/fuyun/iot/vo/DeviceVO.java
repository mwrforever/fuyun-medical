package com.fuyun.iot.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.DeviceAccessMode;
import com.fuyun.iot.enums.DeviceStatus;
import java.time.OffsetDateTime;

/**
 * 设备视图（GET /api/v1/iot/devices 分页与详情、POST 注册出参）：档案与状态机快照面。
 *
 * <p>{@code credentialSecret} 为一机一密一次性透出面（14-iot §9 红线）：仅注册响应非空，
 * 分页/详情恒 null（{@code @JsonInclude(NON_NULL)} 不出网）；落库仅 credentialRef，
 * secret 禁入库禁日志。
 *
 * @param deviceId         设备标识（IoTDA 自然键），非空
 * @param nodeId           设备侧节点标识，可空
 * @param productId        所属注册中心产品标识，可空
 * @param deviceName       设备名称，非空
 * @param deviceType       设备类型（总 Spec 5.1 矩阵 15 类），非空
 * @param accessMode       接入模式：A 直连/B 串口服务器/C 边缘适配器/D HL7 引擎，可空
 * @param wardId           归属病区 ID（未部署设备为空），可空
 * @param credentialRef    一机一密凭证引用（非密钥明文），可空
 * @param status           设备状态机：INACTIVE/ONLINE/OFFLINE/ABNORMAL/DISABLED，非空
 * @param lastOnlineAt     最近上线时刻，可空
 * @param lastOfflineAt    最近离线时刻，可空
 * @param createdAt        创建时刻（数据库维护），可空
 * @param credentialSecret 一机一密凭证明文——仅注册响应一次性透出（其余视图恒 null），可空；禁日志禁落库
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceVO(
        String deviceId,
        String nodeId,
        String productId,
        String deviceName,
        String deviceType,
        DeviceAccessMode accessMode,
        Long wardId,
        String credentialRef,
        DeviceStatus status,
        OffsetDateTime lastOnlineAt,
        OffsetDateTime lastOfflineAt,
        OffsetDateTime createdAt,
        String credentialSecret) {

    /**
     * 实体 → 视图工厂映射（分页/详情面，无 secret）。
     *
     * @param entity 设备档案实体，非空
     * @return 设备视图（credentialSecret 恒 null），非空
     */
    public static DeviceVO from(IotDeviceEntity entity) {
        return of(entity, null);
    }

    /**
     * 实体 → 视图工厂映射（注册响应专用：secret 一次性透出面）。
     *
     * @param entity           设备档案实体，非空
     * @param credentialSecret 一机一密明文，非空；来源：Registry.registerDevice 返回值；禁日志禁落库
     * @return 设备视图（credentialSecret 仅本次响应有效），非空
     */
    public static DeviceVO of(IotDeviceEntity entity, String credentialSecret) {
        return new DeviceVO(
                entity.getDeviceId(),
                entity.getNodeId(),
                entity.getProductId(),
                entity.getDeviceName(),
                entity.getDeviceType(),
                entity.getAccessMode(),
                entity.getWardId(),
                entity.getCredentialRef(),
                entity.getStatus(),
                entity.getLastOnlineAt(),
                entity.getLastOfflineAt(),
                entity.getCreatedAt(),
                credentialSecret);
    }
}
