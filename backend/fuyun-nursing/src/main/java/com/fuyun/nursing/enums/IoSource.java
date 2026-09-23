package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 出入量数据源六值（V804 io_record.source 值域，Spec :111）：MANUAL 工作站手工 / PDA 移动端 /
 * INFUSION_AUTO 输液执行自动带入（P2 执行域） / TRANSFUSION_AUTO 输血自动带入（M12，P4）/
 * ICU_AUTO ICU 自动汇总（M11，P4）/ ICU_MANUAL ICU 手工录入（M11，P4）。P1 生产者仅 MANUAL
 * 与 PDA——其余四值枚举落列预留，P1 录入面收预留值显式拒 NS-1019（无处理路径，禁落卡后悬置，
 * VitalSource.IOT 同款口径）。
 */
public enum IoSource {

    /** 工作站手工录入（P1 生产者） */
    MANUAL("MANUAL"),

    /** PDA 移动端录入（P1 生产者） */
    PDA("PDA"),

    /** 输液执行自动带入（P2 执行域写入方，P1 拒收） */
    INFUSION_AUTO("INFUSION_AUTO"),

    /** 输血自动带入（M12 事件驱动，P4 写入方，P1 拒收） */
    TRANSFUSION_AUTO("TRANSFUSION_AUTO"),

    /** ICU 自动汇总（M11，P4 写入方，P1 拒收） */
    ICU_AUTO("ICU_AUTO"),

    /** ICU 手工录入（M11，P4 写入方，P1 拒收） */
    ICU_MANUAL("ICU_MANUAL");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    IoSource(String code) {
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
     * code → 枚举查询侧（录入入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static IoSource fromCode(String code) {
        for (IoSource value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
