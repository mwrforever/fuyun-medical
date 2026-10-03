package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 扫码核对方式四值（V1106 execution_check_log.check_type 值域）：三向扫码核对三维（腕带/
 * 袋签/执行单——dispatch §3 冻结：患者腕带=visitId 匹配、瓶签=bag_label_code 匹配、执行单=
 * execution_no 匹配）与破码放行留痕（OVERRIDE）。每枚举携带该维核对失败时的 fail_type 判定词
 * （check_result=FAIL 时落行；WRONG_PATIENT/OTHER 两词表值为词表完整性预留，本任务无
 * 产生面）。词表外入参经 {@link #fromCode} 收口 NS-1019 显式拒绝（禁裸异常直穿 500）。
 */
public enum CheckType {

    /** 患者腕带扫描（核对维度=腕带就诊编码与执行单 visit_id 匹配；失败=WRISTBAND_MISMATCH） */
    WRISTBAND("WRISTBAND", "WRISTBAND_MISMATCH"),

    /** 输液袋签扫描（核对维度=袋签码与 infusion_monitor_link.bag_label_code 匹配；失败=BAG_MISMATCH） */
    BAG_LABEL("BAG_LABEL", "BAG_MISMATCH"),

    /** 执行单扫码核对（核对维度=扫码原文与路径执行单号 execution_no 匹配；失败=DEVICE_MISMATCH） */
    DEVICE("DEVICE", "DEVICE_MISMATCH"),

    /** 破码放行留痕（双授权放行动作落行，结论恒 PASS；非扫码维度无失败词） */
    OVERRIDE("OVERRIDE", null);

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    /** 本维核对失败时的 fail_type 判定词（OVERRIDE 无失败面为 null） */
    private final String failType;

    CheckType(String code, String failType) {
        this.code = code;
        this.failType = failType;
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
     * 取本维核对失败判定词。
     *
     * @return fail_type 词表值（V1106 五词表子集）；OVERRIDE 维度无失败面返回 null
     */
    public String failType() {
        return failType;
    }

    /**
     * code → 枚举查询侧（查询入参显式格式校验用，非法值拒收）。
     *
     * @param code 存储值，非空；来源：check 请求 codeType
     * @return 对应枚举常量，非空
     * @throws BizException NS-1019（400）code 无对应枚举常量——词表外值显式拒绝
     */
    public static CheckType fromCode(String code) {
        for (CheckType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "核对方式 code 非法：" + code);
    }
}
