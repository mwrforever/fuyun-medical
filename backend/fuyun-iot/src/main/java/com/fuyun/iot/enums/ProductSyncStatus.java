package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 产品同步状态枚举（iot.iot_product.sync_status 列值域，V1007）：本地镜像与 IoTDA 云端的一致
 * 性状态机。上架落行即 SYNCING（同步中）→ model-sync 对账完成置 SYNCED（已同步）或 MISMATCH
 * （失配——模型属性存在未映射项，遥测按未映射属性原文透传不静默丢弃，14-iot FU-M14-02）；
 * 失配产品由每日对账任务巡检（idx_iot_product_sync_status 查询路径）重新对齐。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射。
 */
public enum ProductSyncStatus {

    /** 同步中：产品上架初态（Registry.createProduct 已受理，物模型对账未完成） */
    SYNCING("SYNCING"),

    /** 已同步：云端模型与本地快照一致，且模型属性全部存在 MDC 映射 */
    SYNCED("SYNCED"),

    /** 失配：模型属性存在未映射项（遥测原文透传并告警，不静默丢弃） */
    MISMATCH("MISMATCH");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ProductSyncStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 SYNCED），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或管理台筛选参数；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或非法入参），
     *                                  建议调用方按参数错误/数据异常处置
     */
    public static ProductSyncStatus fromCode(String code) {
        for (ProductSyncStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的产品同步状态 code: " + code);
    }
}
