package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.Set;

/**
 * 医嘱状态八值（V904 medical_order.status 值域，04-inpatient Spec §3.3 状态机冻结）：
 * 主链 CREATED（已开立待审核）→ AUDITED（审核通过待转抄）→ TRANSFERRED（已转抄）→
 * EXECUTING（执行中）→ COMPLETED（已完成，终态）；侧支 CREATED ⇄ AUDIT_REJECTED
 * （驳回↔修改重提）；AUDITED → CREATED（撤回重审）；AUDITED/TRANSFERRED → CANCELLED
 * （作废，终态）；AUDITED/TRANSFERRED/EXECUTING → STOPPED（停嘱，终态）。
 * 合法迁移表（LEGAL_TRANSITIONS）为唯一裁决面——医嘱状态迁移一律经
 * OrderStateMachineService 按本表校验（04 Spec 红线 2），模块外直写医嘱状态为红线违例。
 */
public enum OrderStatus {

    /** 已开立（待审核；开立初始态，用药类此期语义=待药师审） */
    CREATED("CREATED"),

    /** 审核通过（待转抄） */
    AUDITED("AUDITED"),

    /** 审核驳回（医生修改后重提回 CREATED） */
    AUDIT_REJECTED("AUDIT_REJECTED"),

    /** 已转抄（M05 按此生成执行单） */
    TRANSFERRED("TRANSFERRED"),

    /** 执行中 */
    EXECUTING("EXECUTING"),

    /** 已完成（终态） */
    COMPLETED("COMPLETED"),

    /** 已作废（终态；仅未产生执行面可作废） */
    CANCELLED("CANCELLED"),

    /** 已停嘱（终态；医生停嘱/转科自动停嘱共用终态） */
    STOPPED("STOPPED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    OrderStatus(String code) {
        this.code = code;
    }

    /** 合法迁移表（04 Spec §3.3 全集 13 条边）：from → 可达 to 集合，状态机唯一裁决面 */
    private static final Map<OrderStatus, Set<OrderStatus>> LEGAL_TRANSITIONS = Map.of(
            CREATED, Set.of(AUDITED, AUDIT_REJECTED),
            AUDIT_REJECTED, Set.of(CREATED),
            AUDITED, Set.of(CREATED, TRANSFERRED, CANCELLED, STOPPED),
            TRANSFERRED, Set.of(CANCELLED, EXECUTING, COMPLETED, STOPPED),
            EXECUTING, Set.of(COMPLETED, STOPPED),
            COMPLETED, Set.of(),
            CANCELLED, Set.of(),
            STOPPED, Set.of());

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
     * 迁移合法性裁决（合法迁移表唯一查询入口——OrderStateMachineService 消费）。
     *
     * @param to 目标态，非空
     * @return true=from→to 在合法迁移表内（可达非自身）；终态（COMPLETED/CANCELLED/STOPPED）
     *         对任意目标均 false
     */
    public boolean canTransitionTo(OrderStatus to) {
        return LEGAL_TRANSITIONS.getOrDefault(this, Set.of()).contains(to);
    }

    /**
     * code → 枚举查询侧。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（脏数据防御交调用方）
     */
    public static OrderStatus fromCode(String code) {
        for (OrderStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
