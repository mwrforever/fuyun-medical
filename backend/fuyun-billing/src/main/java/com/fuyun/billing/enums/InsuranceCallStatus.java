package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 医保业务调用状态（insurance_call_log.status 列值域，方案 3.2）：INIT→SENT→SUCCESS/FAILED/TIMEOUT；
 * TIMEOUT/FAILED 进入补偿驱动（COMPENSATED 补偿完成 / WAIVED 人工核销，核销必填 compensate_note）。枚举规范同 FeeStatus。
 */
public enum InsuranceCallStatus {

    /** 已建日志待发送 */
    INIT("INIT"),

    /** 已发送待回执 */
    SENT("SENT"),

    /** 成功（中心结果码 0000） */
    SUCCESS("SUCCESS"),

    /** 业务失败 */
    FAILED("FAILED"),

    /** 超时（进悬挂确认/补偿流程） */
    TIMEOUT("TIMEOUT"),

    /** 已补偿（冲正/重发完成） */
    COMPENSATED("COMPENSATED"),

    /** 已人工核销（WAIVED 必填 compensate_note 留痕） */
    WAIVED("WAIVED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    InsuranceCallStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 INIT），非空；MP 写列与 JSON 序列化均取本值
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
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据），建议上游按数据异常处置
     */
    public static InsuranceCallStatus fromCode(String code) {
        for (InsuranceCallStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的医保调用状态 code: " + code);
    }
}
