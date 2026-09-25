package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 床位属性三值（V903 bed.bed_attr 值域，04-inpatient Spec §4）：包床（PRIVATE）为计费属性
 * 标记而非床位状态——不参与五态状态机，仅供 M13 计费与床位图标记消费；加床（EXTRA）为
 * 机动床形态（全院一张床调度优先级末位）。
 */
public enum BedAttr {

    /** 普通床 */
    NORMAL("NORMAL"),

    /** 包床（单人包间计费属性标记，非状态） */
    PRIVATE("PRIVATE"),

    /** 加床（机动床） */
    EXTRA("EXTRA");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    BedAttr(String code) {
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
    public static BedAttr fromCode(String code) {
        for (BedAttr value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
