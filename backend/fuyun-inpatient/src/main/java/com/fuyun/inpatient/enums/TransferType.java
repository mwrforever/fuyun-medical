package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 护理单元变更类型二值（04-inpatient Spec §3.5 裁决）：转科（跨病区四阶段编排——含医嘱自动
 * 停嘱）与转床（同病区轻量路径——仅床位切换与事件，无停嘱步骤）。两者共用
 * inpatient.visit.transferred 事件与 visit current_ward/current_bed 原子更新，编排深度不同。
 */
public enum TransferType {

    /** 转科（跨病区四阶段编排：停嘱→在途三分→床位流转→事件） */
    WARD_TRANSFER("WARD_TRANSFER"),

    /** 转床（同病区轻量路径：床位流转+事件，无停嘱） */
    BED_CHANGE("BED_CHANGE");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    TransferType(String code) {
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
    public static TransferType fromCode(String code) {
        for (TransferType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
