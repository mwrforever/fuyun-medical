package com.fuyun.system.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 用户账号类型枚举（system.sys_user.user_type 列值域，M01 Spec §4）。
 *
 * <p>区分账号来源与用途：员工日常登录（STAFF）、后台任务与系统集成（SYSTEM）、外部系统对接（API）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射；禁止常量类/整型模拟枚举。
 */
public enum UserType {

    /** 员工账号：有对应员工档案的普通业务用户 */
    STAFF("STAFF"),

    /** 系统账号：后台任务与系统集成专用，无员工档案 */
    SYSTEM("SYSTEM"),

    /** 接口账号：外部系统对接专用，无员工档案 */
    API("API");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    UserType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 STAFF），非空；MP 写列与 JSON 序列化均取本值
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
     * @throws BizException code 无对应枚举常量（400 SYS-1031，BE-C3-05/A.3-3 双层错误模型收口）：
     *                       外部入参转枚举失败按非法请求处置；DB 脏数据触达同口径，禁止静默吞成 null
     */
    public static UserType fromCode(String code) {
        for (UserType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        // 未知 code 收口为 BizException 400（BE-C3-05）：外部入参转枚举失败按非法请求语义返回，禁散落裸 IAE
        throw new BizException(SystemErrorCode.ENUM_VALUE_INVALID, HttpStatus.BAD_REQUEST, "未知的用户账号类型 code: " + code);
    }
}
