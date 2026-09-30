package com.fuyun.ward.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.ward.api.WardErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 呼叫来源枚举（ward_call.source 五值词表，V1100 列注释冻结）：设备源（IOT）经 iot 事件消费
 * 落行（iot.alarm.triggered 输液告急链本 PR 落地；iot.call.triggered 设备呼叫落行注记 PR-3）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + fromCode。
 */
public enum CallSource {

    /** 床头分机 */
    BEDSIDE("BEDSIDE"),

    /** 卫生间紧急按钮 */
    BRROOM("BRROOM"),

    /** 患者 pad */
    PATIENT_PAD("PATIENT_PAD"),

    /** 护士 pad */
    NURSE_PAD("NURSE_PAD"),

    /** IoT 设备（iot 事件消费落行） */
    IOT("IOT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用的业务 code */
    @EnumValue
    private final String code;

    CallSource(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 BEDSIDE），非空；MP 写列与 JSON 序列化均取本值
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
    public static CallSource fromCode(String code) {
        for (CallSource source : values()) {
            if (source.code.equals(code)) {
                return source;
            }
        }
        throw new BizException(WardErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的呼叫来源 code: " + code);
    }
}
