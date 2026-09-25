package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 转抄核对结论两值（V906 order_transfer_log.conclusion 值域，04 Spec §4「三查七对」调研
 * 依据 5）：PASSED（核对通过——转抄面唯一放行结论，AUDITED→TRANSFERRED 迁移与 transferred
 * 事件随行）；REJECTED（核对不符——临床流程退回医生站处理不走转抄端点，应用层拦截
 * IP-1016 不迁移不入台账，词表保完整供后续「核对不符留痕」扩展面）。
 */
public enum CheckConclusion {

    /** 核对通过（三查七对相符——转抄放行） */
    PASSED("PASSED"),

    /** 核对不符（未转抄退回医生站——应用层拦截，不入台账） */
    REJECTED("REJECTED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    CheckConclusion(String code) {
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
    public static CheckConclusion fromCode(String code) {
        for (CheckConclusion value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
