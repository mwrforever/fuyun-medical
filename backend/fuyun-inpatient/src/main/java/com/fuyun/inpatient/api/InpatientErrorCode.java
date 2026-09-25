package com.fuyun.inpatient.api;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.ErrorCode;

/**
 * M04 住院域错误码枚举（IP-xxxx，A.3-4；前缀查证：依模块名惯例取 IP，全仓 grep 零命中——
 * 2026-09-25 核验）。业务异常统一 {@code BizException(InpatientErrorCode.X, HttpStatus, message)}
 * 经全局渲染。IP-1001 起连续无重号，后续新增一律接续顺延。
 */
public enum InpatientErrorCode implements ErrorCode {

    /** 住院证不存在（404；admission_no 无命中） */
    ADMISSION_NOT_FOUND("IP-1001"),
    /** 住院证状态不允许该操作（409；状态机违例：非候床/预约态登记确认、终态再迁移等） */
    ADMISSION_STATE_NOT_ALLOWED("IP-1002"),
    /** 患者被拦截（409；档案冻结或合并中不得办理入院） */
    PATIENT_BLOCKED("IP-1003"),
    /** 床位不存在（404；bed_id 无命中） */
    BED_NOT_FOUND("IP-1004"),
    /** 床位状态不允许该操作（409；消毒中/维修中分配等五态状态机违例） */
    BED_STATE_NOT_ALLOWED("IP-1005"),
    /** 床位已被占用（409；占床条件更新未命中——并发占床硬防线） */
    BED_OCCUPIED("IP-1006"),
    /** 住院就诊不存在（404；visit_id 无命中） */
    VISIT_NOT_FOUND("IP-1007"),
    /** 住院就诊状态不允许该操作（409；在院状态机违例：非在院入科、已出院再操作等） */
    VISIT_STATE_NOT_ALLOWED("IP-1008"),
    /** 医嘱不存在（404；order_no 无命中） */
    ORDER_NOT_FOUND("IP-1009"),
    /** 医嘱状态不允许该操作（409；状态机违例：非法迁移表外路径、终态再迁移等） */
    ORDER_STATE_NOT_ALLOWED("IP-1010"),
    /** 医嘱明细非法（400；剂量/途径/频次校验不过） */
    ORDER_ITEM_INVALID("IP-1011"),
    /** 执业授权未过（403；PracticeCheckPort 校验拒绝，文案含工号脱敏） */
    PRACTICE_FORBIDDEN("IP-1012"),
    /** 过敏史强阳性冲突（409；AllergyChecker 命中禁忌拦截） */
    ALLERGY_CONFLICT("IP-1013"),
    /** 执行计划不存在（404；plan_no 无命中） */
    PLAN_NOT_FOUND("IP-1014"),
    /** 执行计划状态不允许该操作（409；非 PENDING 回签等状态机违例） */
    PLAN_STATE_NOT_ALLOWED("IP-1015"),
    /** 转抄核对非法（400；双人核对缺第二核对人/高危强制项缺失） */
    TRANSFER_CHECK_INVALID("IP-1016"),
    /** 不允许出院（409；放行条件未满足——长期医嘱未终态/在途计划未清零/费用预审未过） */
    DISCHARGE_NOT_ALLOWED("IP-1017"),
    /** 出院申请不存在（404；request_no 无命中） */
    DISCHARGE_REQUEST_NOT_FOUND("IP-1018"),
    /** 会诊单不存在（404；consult_no 无命中） */
    CONSULTATION_NOT_FOUND("IP-1019"),
    /** 会诊单状态不允许该操作（409；会诊状态机违例） */
    CONSULTATION_STATE_NOT_ALLOWED("IP-1020"),
    /** 医嘱频次不存在（404；order_frequency 本地字典无 freq_code） */
    FREQUENCY_NOT_FOUND("IP-1021"),
    /** 入参格式非法（400；结构/格式类校验不过） */
    PARAM_FORMAT_INVALID("IP-1022"),
    /** 数据冲突（409；唯一键冲突/未知病区/归属不符——与 IP-1022「入参格式」严格分开） */
    CONFLICT("IP-1023");

    /** 码值（如 IP-1001），A.2-7 code↔enum 双向映射之 code 侧 */
    private final String code;

    InpatientErrorCode(String code) {
        this.code = code;
    }

    /**
     * 取错误码字符串。
     *
     * @return 码值，非空；经全局渲染输出至 ProblemDetail.properties.errorCode
     */
    @JsonValue
    @Override
    public String getCode() {
        return code;
    }
}
