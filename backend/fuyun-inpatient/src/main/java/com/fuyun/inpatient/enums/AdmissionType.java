package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 入院类型三值（V902 admission.admission_type 值域，04-inpatient Spec §4 冻结）：NORMAL 普通 /
 * EMERGENCY 急诊 / PRE_HOSPITAL 预住院（深圳虚拟床位模式——无床时虚拟登记先完成入院前检查，
 * 床位到位转正式）。EMERGENCY 为候床队列排序第一优先键（急诊优先）。
 */
public enum AdmissionType {

    /** 普通入院 */
    NORMAL("NORMAL"),

    /** 急诊入院（候床队列最高优先） */
    EMERGENCY("EMERGENCY"),

    /** 预住院（虚拟登记，入院前检查先行） */
    PRE_HOSPITAL("PRE_HOSPITAL");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    AdmissionType(String code) {
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
     * @return 对应枚举常量，非空；无匹配返回 null（脏数据防御交调用方定性 IP-1022）
     */
    public static AdmissionType fromCode(String code) {
        for (AdmissionType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
