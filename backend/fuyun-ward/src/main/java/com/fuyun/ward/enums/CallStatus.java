package com.fuyun.ward.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.ward.api.WardErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 呼叫状态枚举（ward_call.status 六态词表，V1100 列注释冻结）：
 * CREATED→ANSWERED→IN_PROGRESS（可选）→COMPLETED 主链；侧支 CREATED/ANSWERED→TRANSFERRED→ANSWERED；
 * CREATED/TRANSFERRED→CANCELLED。合法迁移唯一裁决面归 WardCallServiceImpl（状态机表）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + fromCode。
 */
public enum CallStatus {

    /** 已创建（新呼叫初始态，待应答） */
    CREATED("CREATED"),

    /** 已应答（护士已接，可进入处理中/完成/转接） */
    ANSWERED("ANSWERED"),

    /** 处理中（可选中间态，处理完成即 COMPLETED） */
    IN_PROGRESS("IN_PROGRESS"),

    /** 已完成（终态；必填 result_summary） */
    COMPLETED("COMPLETED"),

    /** 已转接（侧支态，仅可回 ANSWERED） */
    TRANSFERRED("TRANSFERRED"),

    /** 已取消（终态；手工取消或同床位新呼叫合并取消） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用的业务 code */
    @EnumValue
    private final String code;

    CallStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 ANSWERED），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或消费载荷文本；非空
     * @return 对应枚举常量，非空
     * @throws BizException WD-1007（400，词表外 code——脏数据或非法载荷值）；EX-19 收口 A 类：
     *                      外部输入 code 转枚举失败按业务失败渲染，不再以裸 IAE 走 500 通道
     */
    public static CallStatus fromCode(String code) {
        for (CallStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new BizException(WardErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的呼叫状态 code: " + code);
    }
}
