package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 体温测量部位三值（V803 vital_sign_record.temp_site 值域，Spec :108）：ORAL 口温 / AXILLARY
 * 腋温 / RECTAL 肛温。部位为落卡唯一约束第四维（site_key 生成列）与体温单条目键（type_key）、
 * 符号渲染依据（腋温×/口温●/肛温〇，渲染端按 code 决定，服务端仅承载类型权威）。
 */
public enum TempSite {

    /** 口温 */
    ORAL("ORAL"),

    /** 腋温 */
    AXILLARY("AXILLARY"),

    /** 肛温 */
    RECTAL("RECTAL");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    TempSite(String code) {
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
     * code → 枚举查询侧（录入入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static TempSite fromCode(String code) {
        for (TempSite value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
