package com.fuyun.iot.vo;

import com.fuyun.iot.entity.IotProductEntity;
import com.fuyun.iot.enums.ProductSyncStatus;
import java.time.OffsetDateTime;

/**
 * 产品镜像视图（GET /api/v1/iot/products 分页与详情出参）：镜像档案与同步状态面。物模型
 * JSON 快照随视图回显（管理台对账/映射编辑的数据源）。
 *
 * @param productId        注册中心产品标识，非空
 * @param productName      产品名称，非空
 * @param deviceType       设备类型，非空
 * @param protocolType     协议类型，非空
 * @param dataFormat       数据格式，非空
 * @param manufacturerName 厂商名称，可空
 * @param industry         所属行业，可空
 * @param description      产品描述，可空
 * @param modelDefinition  物模型 JSON 快照（服务能力数组形态），可空
 * @param syncStatus       同步状态机：SYNCING/SYNCED/MISMATCH，非空
 * @param createdAt        创建时刻（库端 DEFAULT now() 维护）；POST /products 上架响应直接映射
 *                         插入实体未经回查为 null，分页/详情查询面非空
 */
public record ProductVO(
        String productId,
        String productName,
        String deviceType,
        String protocolType,
        String dataFormat,
        String manufacturerName,
        String industry,
        String description,
        String modelDefinition,
        ProductSyncStatus syncStatus,
        OffsetDateTime createdAt) {

    /**
     * 实体 → 视图工厂映射。
     *
     * @param entity 产品镜像实体，非空
     * @return 产品视图，非空
     */
    public static ProductVO from(IotProductEntity entity) {
        return new ProductVO(
                entity.getProductId(),
                entity.getProductName(),
                entity.getDeviceType(),
                entity.getProtocolType(),
                entity.getDataFormat(),
                entity.getManufacturerName(),
                entity.getIndustry(),
                entity.getDescription(),
                entity.getModelDefinition(),
                entity.getSyncStatus(),
                entity.getCreatedAt());
    }
}
