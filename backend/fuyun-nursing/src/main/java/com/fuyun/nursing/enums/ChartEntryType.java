package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 体温单条目类型三值（V802 temperature_chart_entry.entry_type 值域）：VITAL 体征引用 /
 * SPECIAL_EVENT 特殊事件 / DAILY_VALUE 日行值。唯一约束第四维 type_key 由服务按条目类型写入
 * （VITAL=体温部位 / SPECIAL_EVENT=事件类型 / DAILY_VALUE=日行值类型），使
 * 「(page_id, entry_time, entry_type, 类型键) 唯一」可建部分唯一索引，且同刻不同体温部位的
 * 体征条目互不冲突（Spec :108/:110）。
 */
public enum ChartEntryType {

    /** 体征引用条目（体征转正后由 Task 5 体征域写入；type_key=体温部位，空部位落空串） */
    VITAL("VITAL"),

    /** 特殊事件条目（入院/手术/分娩/物理降温等；type_key=事件类型） */
    SPECIAL_EVENT("SPECIAL_EVENT"),

    /** 日行值条目（大便次数/体重/身高/出入量小结/皮试；type_key=日行值类型） */
    DAILY_VALUE("DAILY_VALUE");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    ChartEntryType(String code) {
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
