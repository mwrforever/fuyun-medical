package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 药品变更广播类型三值（DrugChangedPayload.changeType 值域，V702 id 30 冻结）。
 */
public enum DrugChangeType {

    /** 建档 */
    CREATE("CREATE"),

    /** 档案/状态变更 */
    UPDATE("UPDATE"),

    /** 医保对照维护 */
    MAPPING("MAPPING");

    /** 存储值：JSON 输出（@JsonValue）与 DB 列（@EnumValue 预留）共用 */
    @EnumValue
    private final String code;

    DrugChangeType(String code) {
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
}
