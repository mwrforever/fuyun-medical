package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 发药单状态机七值（Spec :134 逐字冻结）：CREATED(放行入队)→PICKING(批次锁定+追溯码采集)→
 * PICKED(已配待核对)→ISSUED(发药签名完成，发 dispense.completed，终态基点)→PART/FULL_RETURNED；
 * CREATED/PICKING→CANCELLED（处方作废联动释放锁定批次）。
 */
public enum DispenseStatus {

    /** 放行入队 */
    CREATED("CREATED"),

    /** 配药中 */
    PICKING("PICKING"),

    /** 已配待核对 */
    PICKED("PICKED"),

    /** 已发药（终态基点） */
    ISSUED("ISSUED"),

    /** 部分退药 */
    PART_RETURNED("PART_RETURNED"),

    /** 全额退药 */
    FULL_RETURNED("FULL_RETURNED"),

    /** 已取消（释放锁定批次） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    DispenseStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code，非空
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举查询侧。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举（脏数据）
     */
    public static DispenseStatus fromCode(String code) {
        for (DispenseStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的发药单状态 code: " + code);
    }
}
