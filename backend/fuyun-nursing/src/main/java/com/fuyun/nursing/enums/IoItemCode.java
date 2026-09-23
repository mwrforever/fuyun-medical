package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 出入量项目九值（V804 io_record.item_code 值域，Spec :111/调研依据 6）：入量四项（静脉输液/
 * 口服/鼻饲/输血）+ 出量五项（尿/便/呕吐/引流/穿刺液）。每项绑定其所属 io_type——录入时
 * 项目与类型不一致属入参违例（NS-1019）；itemName 展示名由服务端按枚举冗余落库（字典未建时
 * 前端直显，V804 item_name 列注记）。
 */
public enum IoItemCode {

    /** 静脉输液（入量） */
    IV_FLUID(IoType.INTAKE, "IV_FLUID", "静脉输液"),

    /** 口服（入量） */
    ORAL(IoType.INTAKE, "ORAL", "口服"),

    /** 鼻饲（入量） */
    NASOGASTRIC(IoType.INTAKE, "NASOGASTRIC", "鼻饲"),

    /** 输血（入量） */
    BLOOD(IoType.INTAKE, "BLOOD", "输血"),

    /** 尿（出量） */
    URINE(IoType.OUTPUT, "URINE", "尿"),

    /** 便（出量） */
    STOOL(IoType.OUTPUT, "STOOL", "便"),

    /** 呕吐（出量） */
    VOMIT(IoType.OUTPUT, "VOMIT", "呕吐"),

    /** 引流（出量） */
    DRAINAGE(IoType.OUTPUT, "DRAINAGE", "引流"),

    /** 穿刺液（出量） */
    PUNCTURE(IoType.OUTPUT, "PUNCTURE", "穿刺液");

    /** 所属出入量类型（项目与类型一致性校验依据） */
    private final IoType ioType;

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    /** 冗余展示名（item_name 落库值，字典未建时前端直显） */
    private final String displayName;

    IoItemCode(IoType ioType, String code, String displayName) {
        this.ioType = ioType;
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * 取所属出入量类型。
     *
     * @return INTAKE / OUTPUT，非空
     */
    public IoType getIoType() {
        return ioType;
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
     * 取冗余展示名。
     *
     * @return 项目中文名（如 静脉输液），非空
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * code → 枚举查询侧（录入入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static IoItemCode fromCode(String code) {
        for (IoItemCode value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
