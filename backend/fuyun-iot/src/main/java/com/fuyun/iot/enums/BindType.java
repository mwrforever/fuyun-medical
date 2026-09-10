package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 设备绑定模式枚举（iot.iot_binding.bind_type 列值域，M14 Spec §4 绑定快照五元组）。
 *
 * <p>FIXED 固定式安装（绑定落具体床位 bed_id）与 MOBILE 移动式（随患者/转运迁移，床位可空）双模式；
 * 绑定历史只增，解绑走状态迁移（见 {@link BindingStatus}）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + {@code fromCode}。
 */
public enum BindType {

    /** 固定式：设备固定安装于某床位（绑定快照落 bed_id） */
    FIXED("FIXED"),

    /** 移动式：设备随患者/转运场景迁移（床位可空） */
    MOBILE("MOBILE");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    BindType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 FIXED），非空；MP 写列与 JSON 序列化均取本值
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
    public static BindType fromCode(String code) {
        for (BindType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的设备绑定模式 code: " + code);
    }
}
