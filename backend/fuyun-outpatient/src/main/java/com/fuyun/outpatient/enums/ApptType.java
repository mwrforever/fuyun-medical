package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 号别枚举（outpatient.schedule_template.appt_type 与 outpatient.appt_number_pool.appt_type 列值域，
 * M03 FU-M03-01）：词表=system 字典 outpatient.appt-type 条目（V705 种子五类逐字同源，字典供前端
 * 下拉取值源，词表校验以本枚举为权威锚）。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum ApptType {

    /** 普通（GENERAL） */
    GENERAL("GENERAL"),

    /** 专家（EXPERT） */
    EXPERT("EXPERT"),

    /** 专病（SPECIAL_DISEASE） */
    SPECIAL_DISEASE("SPECIAL_DISEASE"),

    /** 急诊（EMERGENCY） */
    EMERGENCY("EMERGENCY"),

    /** 复诊（REVISIT，「一次挂号管三天」回诊专用号别） */
    REVISIT("REVISIT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ApptType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 EXPERT），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或外部入参；非空（列 NOT NULL）
     * @return 对应枚举常量，非空
     * @throws BizException code 无对应枚举常量（词表外入参显式 400 拒绝，OP-1019；EX-19 A 类
     *                      收口：裸 IAE 全局渲染 500 无业务码，外部入参失败前置 400+错误码）
     */
    public static ApptType fromCode(String code) {
        for (ApptType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new BizException(OutpatientErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "号别词表外：" + code);
    }
}
