package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 计费来源（fee_record.charge_source 列值域，Spec §3.1）：区分费用行进入渠道，
 * 手工通道审计要素必填（红线 3）。枚举规范同 FeeStatus。
 */
public enum ChargeSource {

    /** 医嘱关联（门诊开单事件驱动） */
    ORDER_LINKED("ORDER_LINKED"),

    /** 执行关联（执行/发药完成事件驱动） */
    EXEC_LINKED("EXEC_LINKED"),

    /** 日切分解（住院床日费等按日计费） */
    DAY_CUTOVER("DAY_CUTOVER"),

    /** 手工录入（操作者/理由必填，服务层守卫） */
    MANUAL("MANUAL"),

    /** 体检（PEIS 预留，P6 启用） */
    PEIS("PEIS");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ChargeSource(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 ORDER_LINKED），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据），建议上游按数据异常处置
     */
    public static ChargeSource fromCode(String code) {
        for (ChargeSource source : values()) {
            if (source.code.equals(code)) {
                return source;
            }
        }
        throw new IllegalArgumentException("未知的计费来源 code: " + code);
    }
}
