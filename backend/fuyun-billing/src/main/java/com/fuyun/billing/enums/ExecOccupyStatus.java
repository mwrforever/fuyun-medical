package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 执行占用状态（fee_record.exec_occupy_status 列值域，Spec §4）：已发药/已执行/已上传费用
 * 占用为退费硬前置（BILL-1017，须先逆向业务）。枚举规范同 FeeStatus。
 */
public enum ExecOccupyStatus {

    /** 未占用（可直接退费/作废） */
    NONE("NONE"),

    /** 已发药（药房出库占用） */
    DISPENSED("DISPENSED"),

    /** 已执行（检查/治疗执行占用） */
    EXECUTED("EXECUTED"),

    /** 已上传医保（费用上传中心占用，撤销须走 2104） */
    UPLOADED("UPLOADED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ExecOccupyStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 NONE），非空；MP 写列与 JSON 序列化均取本值
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
    public static ExecOccupyStatus fromCode(String code) {
        for (ExecOccupyStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的执行占用状态 code: " + code);
    }
}
