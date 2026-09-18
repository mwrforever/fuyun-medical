package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 价格版本状态（charge_item_price.status 列值域，方案 3.4）：DRAFT→PUBLISHED→EXPIRED，
 * 「当前唯一有效版本」由部分唯一索引硬保证。枚举规范同 FeeStatus。
 */
public enum PriceStatus {

    /** 草稿（待发布） */
    DRAFT("DRAFT"),

    /** 已发布生效（每项目同时至多一条） */
    PUBLISHED("PUBLISHED"),

    /** 已失效（被新版本接替或人工失效） */
    EXPIRED("EXPIRED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    PriceStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 DRAFT），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据），建议上游按数据异常处置
     */
    public static PriceStatus fromCode(String code) {
        for (PriceStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的价格状态 code: " + code);
    }
}
