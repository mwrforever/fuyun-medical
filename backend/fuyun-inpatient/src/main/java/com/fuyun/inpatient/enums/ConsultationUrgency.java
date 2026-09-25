package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Duration;

/**
 * 会诊紧急程度两值（V908 consultation.urgency 值域，04-inpatient Spec FU-M04-09 冻结）：
 * URGENT 急会诊响应时限 30 分钟 / NORMAL 普通会诊响应时限 24 小时（调研依据 13 行业实践）。
 * 响应时限经 response_deadline 承载（申请时点+时限），超时升级为读时惰性判定
 * （GC21④——fy.delay 档位扩展随 W-27 tick 方案 PR-4 一并设计）。
 */
public enum ConsultationUrgency {

    /** 急会诊（响应时限 30 分钟——危重患者快速响应口径） */
    URGENT("URGENT", Duration.ofMinutes(30)),

    /** 普通会诊（响应时限 24 小时——常规会诊响应口径） */
    NORMAL("NORMAL", Duration.ofHours(24));

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    /** 响应时限（申请时点起算，response_deadline = 申请时点 + 本时限） */
    private final Duration responseWindow;

    ConsultationUrgency(String code, Duration responseWindow) {
        this.code = code;
        this.responseWindow = responseWindow;
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
     * 取响应时限。
     *
     * @return 时限（URGENT=30min / NORMAL=24h），非空；response_deadline 计算唯一来源
     */
    public Duration responseWindow() {
        return responseWindow;
    }

    /**
     * code → 枚举查询侧。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（词表外交调用方拒 IP-1022）
     */
    public static ConsultationUrgency fromCode(String code) {
        for (ConsultationUrgency value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
