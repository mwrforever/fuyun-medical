package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 抗菌药物分级四值（drug.antibio_class 列值域，Spec :105）：三级分级对应《抗菌药物临床应用管理办法》
 * （调研依据 7），NONE=非抗菌药。处方权限强校验（特殊使用级=高级职称+会诊同意）随 P3。
 */
public enum AntibacterialClass {

    /** 非抗菌药 */
    NONE("NONE"),

    /** 非限制使用级（各级医师均可处方） */
    UNRESTRICTED("UNRESTRICTED"),

    /** 限制使用级（主治及以上） */
    RESTRICTED("RESTRICTED"),

    /** 特殊使用级（高级职称+会诊同意，P3 强校验） */
    SPECIAL("SPECIAL");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    AntibacterialClass(String code) {
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
    public static AntibacterialClass fromCode(String code) {
        for (AntibacterialClass value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的抗菌药物分级 code: " + code);
    }
}
