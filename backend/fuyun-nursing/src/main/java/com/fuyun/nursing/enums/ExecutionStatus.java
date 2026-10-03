package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 执行单状态六值（V1106 order_execution.status 值域，05-nursing Spec :126 执行单五环节状态链）：
 * {@code CREATED → SIGNED → CHECKED → EXECUTING → COMPLETED} 为主链（非药品类生成即可核对，
 * SIGNED 可跳过），侧支 {@code CREATED/SIGNED/CHECKED → CANCELLED}（停嘱/作废/撤单/出院清理联动）；
 * EXECUTING→CANCELLED 仅限输注中断特殊情形（需护士长权限留痕，归 Task 6 输液面）。COMPLETED/
 * CANCELLED 为终态。生成域本任务仅落 CREATED 与 CANCELLED 两入口，中间态随签收/核对/执行任务实装。
 */
public enum ExecutionStatus {

    /** 已生成（三路生成落库默认态；药品类待病区签收） */
    CREATED("CREATED"),

    /** 已签收（病区签收完成；消费 pharmacy.dispense.completed，非药品类可跳过本态） */
    SIGNED("SIGNED"),

    /** 已核对（三向扫码核对通过；开始执行的硬前置） */
    CHECKED("CHECKED"),

    /** 执行中（开始执行/输注中；输液类挂接监测，Task 6 面实装） */
    EXECUTING("EXECUTING"),

    /** 已完成（执行完成：给药确认/拔针确认；终态） */
    COMPLETED("COMPLETED"),

    /** 已撤销（终态；停嘱/作废/出院清理联动撤销，原因必留痕） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    ExecutionStatus(String code) {
        this.code = code;
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
     * code → 枚举查询侧（查询入参显式格式校验用，非法值拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static ExecutionStatus fromCode(String code) {
        for (ExecutionStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
