package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 就诊状态枚举（outpatient.visit.status 列值域，M03 Spec §5 主状态机）：合法迁移对全集（红线 5，
 * 迁移经状态机单点校验+visit_status_log 每迁必记）=REGISTERED→WAITING、WAITING→IN_CONSULT、
 * IN_CONSULT⇄PENDING_FEE、REGISTERED/WAITING→CANCELLED、IN_CONSULT/PENDING_FEE→FINISHED、
 * REGISTERED→NO_SHOW（声明态）。
 *
 * <p><b>声明态边界（2026-09-20 批复补充约束 1，偏差⑦）</b>：IN_EXECUTION/PENDING_MEDICATION/NO_SHOW
 * 为 visit 维度声明态——仅注册枚举值并标注 P3 触发点（见各值 javadoc：触发事件源与阶段），P1 不写任何
 * 无触发的处理逻辑（无分支/无消费/无定时器），属预留而非死代码。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum VisitStatus {

    /** 已挂号/待分诊（挂号/取号落库初始态） */
    REGISTERED("REGISTERED"),

    /** 候诊（P1 触发点：分诊台报到入队，Task 7 交付） */
    WAITING("WAITING"),

    /** 就诊中（P1 触发点：医生站接诊 admit，Task 8 交付） */
    IN_CONSULT("IN_CONSULT"),

    /** 待缴费（P1 触发点：billing.fee.created 消费推进，Task 8 交付；缴清回 IN_CONSULT） */
    PENDING_FEE("PENDING_FEE"),

    /**
     * 执行中（<b>声明态，P3 触发点</b>：执行回执事件源 lab.specimen.collected /
     * imaging.exam.registered，检查检验/治疗执行阶段；P1 无消费分支）
     */
    IN_EXECUTION("IN_EXECUTION"),

    /**
     * 待取药（<b>声明态，P3 触发点</b>：发药回执事件源 pharmacy.dispense.completed，
     * 已收费待发药阶段；P1 无消费分支）
     */
    PENDING_MEDICATION("PENDING_MEDICATION"),

    /** 诊毕（终态；记录离院去向，FINISHED 后拒绝一切开单/缴费/执行——红线 5） */
    FINISHED("FINISHED"),

    /** 已退号回滚（终态；退号链随 Task 6 交付） */
    CANCELLED("CANCELLED"),

    /**
     * 爽约（<b>声明态</b>：合法迁移对 REGISTERED→NO_SHOW 仅登记于状态机迁移对全集；
     * P3 触发点=当日爽约判定任务，P1 经 appointment 链 NO_SHOW 承载、visit 维度不迁移，
     * 事件登记见 V204 id 35 仅登记无发布点）
     */
    NO_SHOW("NO_SHOW");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    VisitStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 REGISTERED），非空；MP 写列与 JSON 序列化均取本值
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
    public static VisitStatus fromCode(String code) {
        for (VisitStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的就诊状态 code: " + code);
    }
}
