package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 设备绑定状态枚举（iot.iot_binding.status 列值域，M14 Spec §5 绑定生命周期状态机）。
 *
 * <p>状态机：BOUND 绑定中 → UNBINDING 解绑中（解绑动作已受理但未确认，期间遥测不挂新归属）
 * → UNBOUND 已解绑（历史留档终态）。UNBOUND 数据永不删除（数据归属回溯依据）；新绑定必须由
 * BOUND 之外状态新建记录，禁止复用历史记录（V400 部分唯一索引兜底"同一设备同一时刻至多一条绑定中"）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + {@code fromCode}。
 */
public enum BindingStatus {

    /** 绑定中：遥测写入按本状态绑定快照冗余 patient_id/visit_id */
    BOUND("BOUND"),

    /** 解绑中：解绑动作已受理但未确认（期间遥测不挂新归属） */
    UNBINDING("UNBINDING"),

    /** 已解绑：历史留档终态，数据永不删除 */
    UNBOUND("UNBOUND");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    BindingStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 BOUND），非空；MP 写列与 JSON 序列化均取本值
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
    public static BindingStatus fromCode(String code) {
        for (BindingStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的设备绑定状态 code: " + code);
    }
}
