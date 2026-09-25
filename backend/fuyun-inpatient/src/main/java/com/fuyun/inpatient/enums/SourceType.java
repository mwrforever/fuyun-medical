package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 住院证来源四值（V902 admission.source_type 值域，04-inpatient Spec §4 冻结）：OUTPATIENT
 * 门诊转诊（携 source_visit_id 引用门诊 O 型 visit_id，两 visit 各自独立）/ EMERGENCY 急诊
 * 直接登记 / PEIS 体检转介 / OTHER 其他。
 */
public enum SourceType {

    /** 门诊转诊（source_visit_id 必携，M03 转诊关联） */
    OUTPATIENT("OUTPATIENT"),

    /** 急诊直接登记 */
    EMERGENCY("EMERGENCY"),

    /** 体检转介 */
    PEIS("PEIS"),

    /** 其他来源 */
    OTHER("OTHER");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    SourceType(String code) {
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
    public static SourceType fromCode(String code) {
        for (SourceType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
