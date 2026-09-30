package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 告警级别枚举（iot.iot_alarm_rule.alarm_level / iot.iot_alarm.alarm_level 列共用值域）。
 *
 * <p>分级通知面（GC17①降级，FU-M14-08）：三级全部入库并经 WS 推送 /topic/iot/alarm/{wardId}；
 * PDA/手环/短信承载缺位（通知中心后续阶段补齐，注记归 Spec 任务）。风暴抑制④仅对非危急
 * （INFO/WARNING）生效——危急告警风暴期照常推送。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + fromCode。
 */
public enum AlarmLevel {

    /** 提示：一般性状态提示 */
    INFO("INFO"),

    /** 警告：需要关注但非紧急 */
    WARNING("WARNING"),

    /** 危急：需立即处置（风暴期不抑制推送；未确认越升级时限触发升级动作） */
    CRITICAL("CRITICAL");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    AlarmLevel(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 CRITICAL），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或管理台请求体；非空
     * @return 对应枚举常量，非空
     * @throws BizException IOT-1026（400）：code 无对应枚举常量（脏数据或非法请求值），
     *                      建议调用方按校验失败/数据异常处置
     */
    public static AlarmLevel fromCode(String code) {
        for (AlarmLevel level : values()) {
            if (level.code.equals(code)) {
                return level;
            }
        }
        // 词表外 code 收口（BE-C3-05）：BizException 400 + IOT-1026 直达边界渲染 ProblemDetail，
        // MQ 解析链调用方（TelemetryFrameParser/快照读取）就地捕获包装，毒丸/降级语义不变
        throw new BizException(IotErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的告警级别 code: " + code);
    }
}
