package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 医保对照状态（insurance_mapping.status 列值域，FU-M13-01 贯标）：ACTIVE 对照行唯一有效
 * 由部分唯一索引硬保证；贯标硬校验以 ACTIVE 为准（BILL-1006）。枚举规范同 FeeStatus。
 */
public enum MappingStatus {

    /** 有效（参与贯标校验与医保拆分） */
    ACTIVE("ACTIVE"),

    /** 已失效（目录变更后旧对照归档） */
    EXPIRED("EXPIRED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    MappingStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 ACTIVE），非空；MP 写列与 JSON 序列化均取本值
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
    public static MappingStatus fromCode(String code) {
        for (MappingStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的医保对照状态 code: " + code);
    }
}
