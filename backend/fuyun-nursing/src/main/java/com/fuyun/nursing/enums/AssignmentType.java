package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 责任护士分配类型二值（V801 nurse_assignment.assignment_type 值域，FU-M05-01）：
 * PRIMARY 责任组（按患者维度，patient_id 必填）/ BED 管床（按床位维度，bed_no 必填）。
 * 类型与必填组件的一致性校验在分配入口显式执行（不一致 NS-1019 拒收）。
 */
public enum AssignmentType {

    /** 责任组分配（责任患者维度） */
    PRIMARY("PRIMARY"),

    /** 管床分配（床位维度） */
    BED("BED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    AssignmentType(String code) {
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
     * code → 枚举查询侧（分配入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝）
     */
    public static AssignmentType fromCode(String code) {
        for (AssignmentType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
