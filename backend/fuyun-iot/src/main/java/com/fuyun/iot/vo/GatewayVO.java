package com.fuyun.iot.vo;

import com.fuyun.iot.entity.IotGatewayEntity;
import com.fuyun.iot.enums.GatewayMode;
import com.fuyun.iot.enums.GatewayStatus;
import java.time.OffsetDateTime;

/**
 * 边缘网关视图对象（网关管理域出网载体）：V1013 iot_gateway 全字段投影。实体禁直出（宪法 B.1
 * 出网边界），查询/登记/更新响应统一经 {@link #from} 静态工厂转换。
 *
 * @param gatewayId   网关标识（IoTDA 网关设备标识自然键），非空
 * @param gatewayName 网关名称，非空
 * @param mode        接入模式：B 串口服务器/C 边缘适配器，非空
 * @param standbyOf   热备对端网关标识，可空（无双机热备场景为空）
 * @param wardId      归属病区 ID，非空
 * @param status      网关状态：ONLINE 在线/OFFLINE 离线/MAINTENANCE 维护，非空
 * @param createdAt   登记时刻（数据库 DEFAULT now() 承担，落库后回读场景有值），可空
 * @param updatedAt   最近更新时刻（数据库触发器维护），可空
 */
public record GatewayVO(
        String gatewayId,
        String gatewayName,
        GatewayMode mode,
        String standbyOf,
        Long wardId,
        GatewayStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * 实体 → 出网视图（唯一转换出口，字段一一对应浅拷贝）。
     *
     * @param entity 网关实体，非空；来源：mapper 查询或落库组装
     * @return 网关视图，非空
     */
    public static GatewayVO from(IotGatewayEntity entity) {
        return new GatewayVO(
                entity.getGatewayId(),
                entity.getGatewayName(),
                entity.getMode(),
                entity.getStandbyOf(),
                entity.getWardId(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
