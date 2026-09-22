package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 体温单月页状态二值（V802 temperature_chart_page.status 值域）：ACTIVE 进行中 /
 * ARCHIVED 已归档。月页随住院自动创建（ensurePage），归档动作随 P2/P4 病历归档注记引入，
 * P1 仅消费 ACTIVE 态。
 */
public enum ChartPageStatus {

    /** 进行中（住院期间默认态） */
    ACTIVE("ACTIVE"),

    /** 已归档（病历归档后只读；P2/P4 引入写入方） */
    ARCHIVED("ARCHIVED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    ChartPageStatus(String code) {
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
