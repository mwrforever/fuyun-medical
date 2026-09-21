package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 申请单状态枚举（outpatient.clinic_order.status 列值域，M03 医生站开单）：CREATED 开立→
 * PENDING_FEE 费用生成（billing.fee.created 回执推进，Task 8）→CHARGED 缴费放行（order.charged
 * 扇出，Task 10 消费）→CANCELLED 作废（CREATED/PENDING_FEE 可作废）；CHARGED 后拒绝作废、退费
 * 经 M13 退费链逆向（refund.approved，Task 10）。
 *
 * <p><b>声明态边界（2026-09-20 批复补充约束 1，偏差⑦）</b>：IN_EXECUTION/COMPLETED 为状态机合法
 * 迁移对登记值——P3 触发点=执行回执事件源（lab.specimen.collected / imaging.exam.registered 等驱动
 * CHARGED→IN_EXECUTION→COMPLETED），P1 不写任何无触发的处理逻辑（无分支/无消费/无定时器），属
 * 预留而非死代码。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum OrderStatus {

    /** 已开立（开单落库初始态，等待 M13 生成 PENDING 费用） */
    CREATED("CREATED"),

    /** 待缴费（billing.fee.created 回执推进；诊毕在途单据校验口径之一，显式确认可诊毕） */
    PENDING_FEE("PENDING_FEE"),

    /** 已缴费（结算放行；拒绝作废，退费经 M13 退费链逆向） */
    CHARGED("CHARGED"),

    /**
     * 执行中（<b>声明态，P3 触发点</b>：执行回执事件源驱动 CHARGED→IN_EXECUTION；P1 无消费分支）
     */
    IN_EXECUTION("IN_EXECUTION"),

    /**
     * 执行完成（<b>声明态，P3 触发点</b>：执行完成回执驱动 IN_EXECUTION→COMPLETED；P1 无消费分支）
     */
    COMPLETED("COMPLETED"),

    /** 已作废（CREATED/PENDING_FEE 作废终态；RX_REF 行作废经 M06 作废链发起，Spec :119 R2-10） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    OrderStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 CREATED），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取；非空（列 NOT NULL）
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据），建议上游按数据异常处置
     */
    public static OrderStatus fromCode(String code) {
        for (OrderStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的申请单状态 code: " + code);
    }
}
