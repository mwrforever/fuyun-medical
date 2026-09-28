package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.GatewayMode;
import com.fuyun.iot.enums.GatewayStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 边缘网关档案实体（iot.iot_gateway，V1013 迁移，FU-M14-12）：模式 B/C 网关本地 CRUD 面载体。
 *
 * <p>主键偏离声明（IotDeviceEntity 同款口径 / V1013 文件头）：主键 = gateway_id（IoTDA 网关设备
 * 标识自然键，iot_device.gateway_id 引用列同形），{@code @TableId(type = INPUT)}——网关身份由
 * 外部系统分配（经 IoTDA 注册，Registry 直通），本地代理 id 无消费方，宪法 A.4.3-16 的 ASSIGN_ID
 * 约束针对代理主键实体，自然键实体不适用。状态列使用 {@link GatewayMode}/{@link GatewayStatus}
 * 枚举（MP @EnumValue 自动映射 VARCHAR 列）；updated_at 由数据库触发器统一维护（V1 公共函数，
 * 宪法 A.4.2-9），应用层不写时间戳列。拓扑面（子设备挂载）经 IoTDA 维护不落列——Spec「拓扑经
 * IoTDA 维护」，本地仅档案 CRUD 与联动展示。
 */
@Getter
@Setter
@TableName("iot.iot_gateway")
public class IotGatewayEntity {

    /** 主键：IoTDA 网关设备标识自然键（@TableId(INPUT)，由外部系统分配后写入，禁止本地生成） */
    @TableId(value = "gateway_id", type = IdType.INPUT)
    private String gatewayId;

    /** 网关名称（管理台展示名） */
    private String gatewayName;

    /** 接入模式：B 串口服务器/C 边缘适配器（模式 A/D 不经网关，不入本词表） */
    private GatewayMode mode;

    /** 热备对端网关标识（总 Spec 5.2 双网关热备；可空：无双机热备场景；服务层校验禁自引用/成环），可空 */
    private String standbyOf;

    /** 归属病区 ID（网关服务病区，管理台按病区过滤主路径） */
    private Long wardId;

    /** 网关状态：ONLINE 在线/OFFLINE 离线/MAINTENANCE 维护（档案态，实时面归设备档案） */
    private GatewayStatus status;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护（V1 公共函数），应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：种子/系统操作为 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：同 createdBy 口径 */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
