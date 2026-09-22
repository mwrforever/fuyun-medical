package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 体征复核状态三值（V803 vital_sign_record.review_status 值域，Spec :130 复核流状态机）：
 * PENDING_REVIEW（待复核：IoT 冲突对或 SUSPECT 质量，P2 写入方）→ CONFIRMED（已转正，入体温单
 * 权威栏）/ REJECTED（已驳回，不入权威栏）。P1 手工/PDA 点测录入即 CONFIRMED（Spec :130
 * 「护士手工/PDA 点测值直接 CONFIRMED」）；待复核行 P1 经复核端点转正/驳回（IT 直接构造验证）。
 */
public enum VitalReviewStatus {

    /** 待复核（IoT 冲突对或 SUSPECT 质量，P2 写入方；P1 仅复核端点可达） */
    PENDING_REVIEW("PENDING_REVIEW"),

    /** 已转正（入体温单权威栏；手工/PDA 点测录入即转正） */
    CONFIRMED("CONFIRMED"),

    /** 已驳回（不入权威栏，驳回原因落 remark 留痕） */
    REJECTED("REJECTED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    VitalReviewStatus(String code) {
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
