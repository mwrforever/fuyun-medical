package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 离院方式六值（V907 discharge_request.discharge_way 值域，04-inpatient Spec §4/调研依据 11
 * 「病案首页离院方式代码」词表冻结）：code 即病案首页标准代码（数字字符），离院确认时誊写至
 * inpatient_visit.discharge_way（病案统计口径）。医嘱离院为常态主路径；死亡/非医嘱离院等
 * 特殊去向经病案首页统计上报。
 */
public enum DischargeWay {

    /** 医嘱离院（常态主路径——医生评估后按医嘱离院） */
    BY_ORDER("1", "医嘱离院"),

    /** 医嘱转院（按医嘱转往其他医院） */
    TRANSFER_HOSPITAL("2", "医嘱转院"),

    /** 转社区卫生服务机构/乡镇卫生院（下转基层续康） */
    TO_COMMUNITY("3", "转社区卫生服务机构/乡镇卫生院"),

    /** 非医嘱离院（患者或家属要求自动离院——病程须留知情记录） */
    SELF_DISCHARGE("4", "非医嘱离院"),

    /** 死亡 */
    DEATH("5", "死亡"),

    /** 其他 */
    OTHER("9", "其他");

    /** 存储值：病案首页代码（数字字符；DB 列写入 @EnumValue 与 JSON 输出 @JsonValue 共用） */
    @EnumValue
    private final String code;

    /** 中文名称（病案首页词表语义，日志与文档用） */
    private final String label;

    DischargeWay(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 取存储值（病案首页代码）。
     *
     * @return 业务 code，非空
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 取中文名称。
     *
     * @return 病案首页词表中文名，非空
     */
    public String getLabel() {
        return label;
    }

    /**
     * code → 枚举查询侧。
     *
     * @param code 病案首页代码（"1"/"2"/"3"/"4"/"5"/"9"），非空
     * @return 对应枚举常量，非空；无匹配返回 null（词表外交调用方拒 IP-1022）
     */
    public static DischargeWay fromCode(String code) {
        for (DischargeWay value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
