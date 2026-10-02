package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 不良事件处置状态三值（V1107 adverse_event.status 值域，05-nursing Spec FU-M05-09）：
 * {@code REPORTED → HANDLING → CLOSED} 为主链（关闭随附 RCA 与整改措施），
 * {@code HANDLING → REPORTED} 退回侧支（处置不充分退回重处——独立 return 端点承载）。
 * CLOSED 为终态；非法迁移 NS-1026 拒绝。tick 超时提醒扫描段限定 REPORTED 面（不改状态）。
 */
public enum AdverseEventStatus {

    /** 已上报（落库默认态；I/II 级 24 小时时限判定基准态） */
    REPORTED("REPORTED"),

    /** 处置中（REPORTED→HANDLING；handler_id 落值） */
    HANDLING("HANDLING"),

    /** 已关闭（HANDLING→CLOSED；RCA 与整改措施随关闭落库；终态） */
    CLOSED("CLOSED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    AdverseEventStatus(String code) {
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
     * code → 枚举查询侧（查询入参显式格式校验用，非法值拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static AdverseEventStatus fromCode(String code) {
        for (AdverseEventStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
