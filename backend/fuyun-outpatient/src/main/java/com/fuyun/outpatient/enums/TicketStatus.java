package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 候诊票据状态枚举（outpatient.queue_ticket.status 列值域，M03 候诊队列）：
 * WAITING→CALLED（叫号）/CALLED→PASSED（过号）/PASSED→CALLED（重呼）/WAITING|PASSED→CANCELLED
 * （跨队列转接放旧票）/CALLED→SERVING（接诊）为 P1 在用迁移对；SERVED 为声明态。
 *
 * <p><b>声明态边界（2026-09-20 批复补充约束 1，偏差⑦同口径）</b>：SERVED 为 ticket 维度声明态——
 * 仅注册枚举值并标注触发点（Task 8 诊毕链 FINISHED 联动），P1 本任务不写任何无触发的处理逻辑
 * （无分支/无消费/无定时器），属预留而非死代码。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum TicketStatus {

    /** 候诊（报到建行初始态；跨队列转接/调级的在队态） */
    WAITING("WAITING"),

    /** 已叫号（Task 7 call/recall 写入；叫号≠接诊，visit 保持 WAITING） */
    CALLED("CALLED"),

    /** 就诊中（声明触发点：Task 8 接诊 admit 联动 CALLED→SERVING+serve_time 回填） */
    SERVING("SERVING"),

    /**
     * 已诊毕（<b>声明态，Task 8 触发点</b>：诊毕 finish 联动 SERVING→SERVED；P1 本任务无写入口）
     */
    SERVED("SERVED"),

    /** 过号（CALLED 未到→PASSED，以降级分重入 ZSET 保持 WAITING 语义，Spec :106） */
    PASSED("PASSED"),

    /** 已取消（跨队列转接放旧票；P1 无独立退票端点） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    TicketStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 WAITING），非空；MP 写列与 JSON 序列化均取本值
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
    public static TicketStatus fromCode(String code) {
        for (TicketStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的候诊票据状态 code: " + code);
    }
}
