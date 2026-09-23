package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 体温单特殊事件十值（V802 temperature_chart_entry.special_event_type 值域，Spec 体温单
 * 内容要素）：入院/手术/分娩/转出/出院/死亡/物理降温/脉搏短绌起止/呼吸心跳停止。符号渲染
 * 规则（物理降温红圈红虚线、脉搏短绌短红线等）由前端按 type_key 绘制，服务端仅承载类型权威。
 */
public enum SpecialEventType {

    /** 入院 */
    ADMISSION("ADMISSION"),

    /** 手术（术后天数标注依据归 M10，本条目仅体温单时刻标注） */
    SURGERY("SURGERY"),

    /** 分娩 */
    DELIVERY("DELIVERY"),

    /** 转出（转科离开本病区时刻标注） */
    TRANSFER_OUT("TRANSFER_OUT"),

    /** 出院 */
    DISCHARGE("DISCHARGE"),

    /** 死亡 */
    DEATH("DEATH"),

    /** 物理降温（30 分钟后复测体温注记随 P2 体征域联动） */
    PHYSICAL_COOLING("PHYSICAL_COOLING"),

    /** 脉搏短绌开始（短红线渲染起点） */
    PULSE_DEFICIT_START("PULSE_DEFICIT_START"),

    /** 脉搏短绌结束（短红线渲染终点） */
    PULSE_DEFICIT_END("PULSE_DEFICIT_END"),

    /** 呼吸心跳停止 */
    CARDIAC_ARREST("CARDIAC_ARREST");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    SpecialEventType(String code) {
        this.code = code;
    }

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
     * code → 枚举查询侧（特殊事件入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static SpecialEventType fromCode(String code) {
        for (SpecialEventType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
