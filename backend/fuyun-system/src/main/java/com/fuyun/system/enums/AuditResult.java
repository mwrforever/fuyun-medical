package com.fuyun.system.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 审计结果枚举（system.audit_log.result 列值域，M01 Spec §4）。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射；禁止常量类/整型模拟枚举。
 */
public enum AuditResult {

    /** 成功 */
    SUCCESS("SUCCESS"),

    /** 失败（fail_reason 记脱敏截断后的原因） */
    FAIL("FAIL");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    AuditResult(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 SUCCESS），非空；MP 写列与 JSON 序列化均取本值
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
    public static AuditResult fromCode(String code) {
        for (AuditResult result : values()) {
            if (result.code.equals(code)) {
                return result;
            }
        }
        // 未知 code 收口为 BizException 400（BE-C3-05）：外部入参转枚举失败按非法请求语义返回，禁散落裸 IAE
        throw new BizException(SystemErrorCode.ENUM_VALUE_INVALID, HttpStatus.BAD_REQUEST, "未知的审计结果 code: " + code);
    }
}
