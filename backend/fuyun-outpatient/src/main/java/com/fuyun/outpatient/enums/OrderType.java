package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 申请单类型枚举（outpatient.clinic_order.order_type 列值域，M03 医生站开单）：EXAM/LAB/TREATMENT/
 * DISPOSAL/MATERIAL 为医生站开单五类（create 端点显式必填，词表外 OP-1019 拒绝）；RX_REF 处方引用行
 * 由 M06 处方生效链写入（ext_ref=M06 rxNo，P1 仅 RX_REF 写 ext_ref），不经医生站开单端点创建。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum OrderType {

    /** 检查申请单（影像/功能检查项目行） */
    EXAM("EXAM"),

    /** 检验申请单（实验室检验项目行） */
    LAB("LAB"),

    /** 治疗申请单（治疗/康复项目行） */
    TREATMENT("TREATMENT"),

    /** 处置申请单（门诊处置/操作项目行） */
    DISPOSAL("DISPOSAL"),

    /** 材料申请单（卫生材料/耗材行） */
    MATERIAL("MATERIAL"),

    /** 处方引用行（ext_ref=M06 rxNo；M06 处方生效链写入，作废经 M06 作废链发起——Spec :119 R2-10） */
    RX_REF("RX_REF");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    OrderType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 EXAM），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：API 入参/DB 列读取；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（调用方转 OP-1019 入参词表外拒绝）
     */
    public static OrderType fromCode(String code) {
        for (OrderType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的申请单类型 code: " + code);
    }
}
