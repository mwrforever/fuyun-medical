package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 体征数据源三值（V803 vital_sign_record.source 值域，Spec :108 三源归一）：MANUAL 工作站手工 /
 * PDA 移动端（含一体机直采，属移动端手工形态）/ IOT 连续遥测。P1 落地双源（MANUAL/PDA 点测
 * 直落 CONFIRMED）；IOT 源（周期拉取/质量闸门/同窗冲突仲裁/自动转正）归 P2——P1 录入面收
 * IOT 值显式拒 NS-1019（无处理路径，禁落卡后悬置）。
 */
public enum VitalSource {

    /** 工作站手工录入 */
    MANUAL("MANUAL"),

    /** PDA 移动端录入（含一体机直采） */
    PDA("PDA"),

    /** IoT 连续遥测（P2 写入方；P1 录入面拒收） */
    IOT("IOT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    VitalSource(String code) {
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
     * code → 枚举查询侧（录入入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static VitalSource fromCode(String code) {
        for (VitalSource value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
