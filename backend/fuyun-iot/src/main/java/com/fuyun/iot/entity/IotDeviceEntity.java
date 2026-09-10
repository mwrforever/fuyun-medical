package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.DeviceAccessMode;
import com.fuyun.iot.enums.DeviceStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备档案实体（iot.iot_device，V400 迁移）：IoTDA 设备唯一档案与状态机载体。
 *
 * <p>主键偏离声明（BRIEF-PR4-01 §1.2 / V400 文件头）：主键 = device_id（IoTDA 设备标识自然键），
 * {@code @TableId(type = INPUT)}——设备身份由外部系统分配，本地代理 id 无消费方，宪法 A.4.3-16
 * 的 ASSIGN_ID 约束针对代理主键实体，自然键实体不适用（PR-4 表外申报项）。
 * 状态列使用 {@link DeviceStatus}/{@link DeviceAccessMode} 枚举（MP @EnumValue 自动映射 VARCHAR 列）；
 * updated_at 由数据库触发器统一维护（V1 公共函数，宪法 A.4.2-9），应用层不写时间戳列。
 * 敏感红线：credentialRef 仅存一机一密凭证引用，密钥明文禁入此表及任何表（14-iot §9）。
 */
@Getter
@Setter
@TableName("iot.iot_device")
public class IotDeviceEntity {

    /** 主键：IoTDA 设备标识自然键（@TableId(INPUT)，由外部系统分配后写入，禁止本地生成） */
    @TableId(value = "device_id", type = IdType.INPUT)
    private String deviceId;

    /** 设备侧标识（物模型节点），可空 */
    private String nodeId;

    /** IoTDA 产品标识（产品镜像表随 P1，先落引用列避 ALTER），可空 */
    private String productId;

    /** 设备名称 */
    private String deviceName;

    /** 设备类型（总 Spec 5.1 矩阵 15 类，P0 无 CRUD 消费方保持字符串直传） */
    private String deviceType;

    /** 接入模式：A 直连/B 串口服务器/C 边缘适配器/D HL7 引擎 */
    private DeviceAccessMode accessMode;

    /** 所属网关（直连设备为空），可空 */
    private String gatewayId;

    /** 归属病区 ID（未部署设备为空），可空 */
    private Long wardId;

    /** 固定安装设备当前位置（移动式设备为空），可空 */
    private Long bedId;

    /** M15 资产号引用（展示级冗余，权威在 asset 域），可空 */
    private String assetRef;

    /** 一机一密凭证引用（密钥明文禁入库，14-iot 红线），可空 */
    private String credentialRef;

    /** 设备状态机：INACTIVE/ONLINE/OFFLINE/ABNORMAL/DISABLED（14-iot §5） */
    private DeviceStatus status;

    /** 最近上线时刻（IoTDA 设备状态数据源驱动，状态帧 apply 时分派写入），可空 */
    private OffsetDateTime lastOnlineAt;

    /** 最近离线时刻（同 lastOnlineAt 数据源），可空 */
    private OffsetDateTime lastOfflineAt;

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
