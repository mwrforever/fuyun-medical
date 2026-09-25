package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 会诊级别三值（V908 consultation.level 词表，04-inpatient Spec FU-M04-09「会诊级别管理」）：
 * DEPT 科内 / HOSPITAL 院内为在用值；MDT 多学科为<b>预留词表值</b>——多学科会诊完整流转
 * （多科受邀/主持人/沉淀记录）随 P3 会诊域完整化引入，本 PR 仅词表与列承载（申请面可传，
 * 零专属业务分支）。
 */
public enum ConsultationLevel {

    /** 科内会诊（本科室范围内响应，缺省级别） */
    DEPT("DEPT"),

    /** 院内会诊（跨科室受邀，全院响应面） */
    HOSPITAL("HOSPITAL"),

    /** 多学科会诊 MDT（预留值——多科联合诊疗完整流转随 P3 会诊域完整化引入） */
    MDT("MDT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    ConsultationLevel(String code) {
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
     * code → 枚举查询侧。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（词表外交调用方拒 IP-1022）
     */
    public static ConsultationLevel fromCode(String code) {
        for (ConsultationLevel value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
