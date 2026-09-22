package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 病区患者视图行来源二值（V801 nursing_ward_patient.source 值域）：MANUAL 手工（P1 过渡通道
 * POST /ward-patients 写入）/ EVENT 事件（P2 由 inpatient.visit.* + bed.changed 事件链写入）。
 * 来源标记为过渡通道退役审计依据：P2 事件链上线后 MANUAL 新增即异常信号。
 */
public enum WardPatientSource {

    /** P1 过渡通道手工登记 */
    MANUAL("MANUAL"),

    /** P2 事件链写入（当前无生产者，预留值域占位） */
    EVENT("EVENT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    WardPatientSource(String code) {
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
