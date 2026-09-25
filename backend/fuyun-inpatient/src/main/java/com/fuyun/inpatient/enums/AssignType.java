package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 床位占用流水类型三值（V903 bed_assign.assign_type 值域，04-inpatient Spec §4）：开账动作
 * 来源标记——入院分配（admit-ward/直接分配）、转床（同病区轻量路径）、转科转入（四阶段编排）。
 */
public enum AssignType {

    /** 入院分配（入科确认占床/床位直接分配） */
    ADMISSION("ADMISSION"),

    /** 转床（同病区床位切换轻量路径） */
    BED_CHANGE("BED_CHANGE"),

    /** 转科转入（跨病区四阶段编排） */
    WARD_TRANSFER("WARD_TRANSFER");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    AssignType(String code) {
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
     * @return 对应枚举常量，非空；无匹配返回 null（脏数据防御交调用方）
     */
    public static AssignType fromCode(String code) {
        for (AssignType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
