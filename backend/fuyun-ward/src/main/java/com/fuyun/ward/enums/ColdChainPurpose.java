package com.fuyun.ward.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.ward.api.WardErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 冷链用途枚举（cold_chain_archive.purpose 四值词表，V1101 列注释冻结）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + fromCode。
 */
public enum ColdChainPurpose {

    /** 疫苗 */
    VACCINE("VACCINE"),

    /** 血液 */
    BLOOD("BLOOD"),

    /** 试剂 */
    REAGENT("REAGENT"),

    /** 药品 */
    PHARMA("PHARMA");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用的业务 code */
    @EnumValue
    private final String code;

    ColdChainPurpose(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 VACCINE），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或请求体文本；非空
     * @return 对应枚举常量，非空
     * @throws BizException WD-1007（400，词表外 code——脏数据或非法请求值）；EX-19 收口 A 类：
     *                      外部输入 code 转枚举失败按业务失败渲染，不再以裸 IAE 走 500 通道
     */
    public static ColdChainPurpose fromCode(String code) {
        for (ColdChainPurpose purpose : values()) {
            if (purpose.code.equals(code)) {
                return purpose;
            }
        }
        throw new BizException(WardErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的冷链用途 code: " + code);
    }
}
