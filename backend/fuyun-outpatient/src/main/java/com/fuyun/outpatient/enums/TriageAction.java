package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 分诊动作枚举（outpatient.triage_record.action 列值域，M03 分诊台四类动作）：CHECK_IN 报到入队、
 * RE_TRIAGE 二次分诊定医生、LEVEL_ADJUST 调级、QUEUE_TRANSFER 跨队列转接。只增留痕语义——
 * 业务纠错以新动作行表达，禁更新历史行。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum TriageAction {

    /** 报到入队（visit REGISTERED→WAITING + queue_ticket 建行 + ZSET 入队） */
    CHECK_IN("CHECK_IN"),

    /** 二次分诊定医生（ticket.doctor_id 指派，分值不变） */
    RE_TRIAGE("RE_TRIAGE"),

    /** 调级（重算优先级分，ZSET 更新 score，票号不变——过号降级重排不改号 Spec :106） */
    LEVEL_ADJUST("LEVEL_ADJUST"),

    /** 跨队列转接（旧队 ZSET remove+票 CANCELLED，新队建票重算分） */
    QUEUE_TRANSFER("QUEUE_TRANSFER");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    TriageAction(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 CHECK_IN），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：API 入参/DB 列读取；可空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（调用方转 OP-1019 入参词表外拒绝）
     */
    public static TriageAction fromCode(String code) {
        for (TriageAction action : values()) {
            if (action.code.equals(code)) {
                return action;
            }
        }
        throw new IllegalArgumentException("未知的分诊动作 code: " + code);
    }
}
