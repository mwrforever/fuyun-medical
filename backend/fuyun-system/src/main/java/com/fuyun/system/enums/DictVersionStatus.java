package com.fuyun.system.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 字典版本状态枚举（system.dict_version.status 列值域，M01 Spec §5 版本状态机）。
 *
 * <p>状态机：DRAFT → PUBLISHED（发布，同 type 旧 PUBLISHED 随即置 DEPRECATED）→ DEPRECATED（终态）；
 * 仅 DRAFT 可编辑条目，仅 DRAFT 可发布。发布即生效（P0，effective_at=now），fy.delay 定时生效随 P1。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射；禁止常量类/整型模拟枚举。
 */
public enum DictVersionStatus {

    /** 草稿：可编辑条目、可发布 */
    DRAFT("DRAFT"),

    /** 已发布：对外生效（同一 type 同一时刻仅一个） */
    PUBLISHED("PUBLISHED"),

    /** 已废弃：被更新版本替代的终态 */
    DEPRECATED("DEPRECATED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    DictVersionStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 PUBLISHED），非空；MP 写列与 JSON 序列化均取本值
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
    public static DictVersionStatus fromCode(String code) {
        for (DictVersionStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        // 未知 code 收口为 BizException 400（BE-C3-05）：外部入参转枚举失败按非法请求语义返回，禁散落裸 IAE
        throw new BizException(SystemErrorCode.ENUM_VALUE_INVALID, HttpStatus.BAD_REQUEST, "未知的字典版本状态 code: " + code);
    }
}
