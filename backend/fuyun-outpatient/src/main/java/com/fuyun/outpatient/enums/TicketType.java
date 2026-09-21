package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 候诊票别枚举（outpatient.queue_ticket.ticket_type 列值域，M03 候诊队列）：P1 报到建票按
 * visit.is_revisit 派生 FIRST/RETURN（RETURN 携回诊/复诊类别分 300，冻结公式偏差⑨）；
 * VISIT/EXTRA 为词表登记位（EXTRA 加号票随加号域后续任务接入，VISIT 就诊票别预留）。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum TicketType {

    /** 初诊票（报到建票默认，回诊/复诊类别分不触发） */
    FIRST("FIRST"),

    /** 就诊票（词表预留位：P1 报到派生不产出，无写入口） */
    VISIT("VISIT"),

    /** 回诊/复诊票（visit.is_revisit=1 派生；类别分 300 与急诊分级取最高单项不叠加） */
    RETURN("RETURN"),

    /** 加号票（词表登记位：加号入队随加号域后续任务接入，P1 无写入口） */
    EXTRA("EXTRA");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    TicketType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 FIRST），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取；可空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据），建议上游按数据异常处置
     */
    public static TicketType fromCode(String code) {
        for (TicketType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的候诊票别 code: " + code);
    }
}
