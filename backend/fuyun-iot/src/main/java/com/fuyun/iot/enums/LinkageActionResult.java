package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 联动动作执行结果枚举（iot.iot_linkage_log.action_result 列值域，FU-M14-10）。
 *
 * <p>SUCCESS/FAILED 为动作终态（FAILED 可人工重推 POST /linkage-logs/{no}/retry）；PENDING 为
 * 暂存态——目标业务域未上线（ward 呼叫/M16 播报/M05 护理任务）时动作承载为留痕，error_msg 注记
 * 域缺位原因，回接方（Task 12/PR-3）收口。枚举规范（backend 宪法 A.2-7）：code 字段 +
 * {@code @EnumValue}（MP DB 列映射）+ {@code @JsonValue}（JSON 输出 code）+ {@code fromCode}
 * 双向映射。
 */
public enum LinkageActionResult {

    /** 执行成功（终态）：动作回执正常 */
    SUCCESS("SUCCESS"),

    /** 执行失败（终态）：自动重试耗尽，可人工重推 */
    FAILED("FAILED"),

    /** 暂存（非终态）：目标业务域未上线，留痕待回接方收口 */
    PENDING("PENDING");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    LinkageActionResult(String code) {
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
     * @param code 存储值，来源：DB 列读取；非空
     * @return 对应枚举常量，非空
     * @throws BizException IOT-1026（400）：code 无对应枚举常量（脏数据），建议调用方按数据异常处置
     */
    public static LinkageActionResult fromCode(String code) {
        for (LinkageActionResult result : values()) {
            if (result.code.equals(code)) {
                return result;
            }
        }
        // 词表外 code 收口（BE-C3-05）：BizException 400 + IOT-1026 直达边界渲染 ProblemDetail，
        // MQ 解析链调用方（TelemetryFrameParser/快照读取）就地捕获包装，毒丸/降级语义不变
        throw new BizException(IotErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的联动执行结果 code: " + code);
    }
}
