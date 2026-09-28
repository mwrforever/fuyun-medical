package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.MismatchStrategy;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 物模型属性 MDC 映射实体（iot.iot_metric_mapping，V1007 迁移）：物模型属性 → MDC 编码跨品牌
 * 归一（FU-M14-02）。行随管理台 PUT 全量替换重建（逻辑删旧行 + 插入新行）；失配策略默认
 * RAW_PASSTHROUGH（失配期间遥测原文透传不静默丢弃）。
 */
@Getter
@Setter
@TableName("iot.iot_metric_mapping")
public class IotMetricMappingEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 应用层生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** IoTDA 产品标识（关联 iot_product 自然键） */
    private String productId;

    /** 物模型属性名（model_definition services[].properties[].name） */
    private String propertyName;

    /** MDC 编码（逻辑引用 iot_metric_dict，应用层校验存在性） */
    private String metricCode;

    /** 失配策略：RAW_PASSTHROUGH 原文透传（当前唯一词表项） */
    private MismatchStrategy mismatchStrategy;

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
