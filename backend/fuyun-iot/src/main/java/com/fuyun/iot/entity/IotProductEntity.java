package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.ProductSyncStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 产品镜像实体（iot.iot_product，V1007 迁移）：IoTDA 产品本地镜像与物模型快照载体。
 *
 * <p>主键偏离声明（V1007 文件头）：主键 = product_id（IoTDA 产品标识自然键），
 * {@code @TableId(type = INPUT)}——产品身份由注册中心分配，本地代理 id 无消费方（V400
 * iot_device 同款申报）。状态列使用 {@link ProductSyncStatus} 枚举（MP @EnumValue 自动映射）；
 * updated_at 由数据库触发器统一维护（V1 公共函数），应用层不写时间戳列。
 */
@Getter
@Setter
@TableName("iot.iot_product")
public class IotProductEntity {

    /** 主键：IoTDA 产品标识自然键（@TableId(INPUT)，注册中心分配后写入，禁止本地生成） */
    @TableId(value = "product_id", type = IdType.INPUT)
    private String productId;

    /** 产品名称（管理台展示名） */
    private String productName;

    /** 设备类型（总 Spec 5.1 矩阵 15 类） */
    private String deviceType;

    /** 协议类型（MQTT/LwM2M/HTTPS/Modbus 等） */
    private String protocolType;

    /** 数据格式：JSON/二进制 */
    private String dataFormat;

    /** 厂商名称，可空 */
    private String manufacturerName;

    /** 所属行业，可空 */
    private String industry;

    /** 产品描述，可空 */
    private String description;

    /** 物模型 JSON 快照（服务能力数组形态，注册中心同步权威源），可空 */
    private String modelDefinition;

    /** 同步状态机：SYNCING/SYNCED/MISMATCH（失配即告警对账，14-iot FU-M14-02） */
    private ProductSyncStatus syncStatus;

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
