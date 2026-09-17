package com.fuyun.patient.api;

import com.fuyun.common.exception.ErrorCode;

/**
 * M02 患者主索引模块错误码枚举（PAT-xxxx，backend 宪法 A.3-4；前缀查证：M02 Spec 未定义，
 * 依「模块助记-4 位数字」惯例取 PAT，全仓既有 SYS-/INT- 无冲突，2026-09-16 核验）。
 *
 * <p>落 api 包为宪法 B.1 明文（错误码枚举属对外契约）；业务异常抛
 * {@code BizException(PatientErrorCode.XXX, HttpStatus, message)}，由全局渲染器输出
 * RFC 9457 ProblemDetail（properties.errorCode/traceId）。
 */
public enum PatientErrorCode implements ErrorCode {

    /** 患者不存在（404；patientId 或标识解析无命中） */
    PATIENT_NOT_FOUND("PAT-1001"),

    /** 标识值已挂接其他档案（409；(identifier_type, value_hash) 唯一约束命中） */
    IDENTIFIER_ALREADY_BOUND("PAT-1002"),

    /** 患者已合并（409；MERGED 档案不可发起任何新就诊/状态操作，M02 §5） */
    PATIENT_ALREADY_MERGED("PAT-1003"),

    /** 患者已冻结（409；重复冻结） */
    PATIENT_ALREADY_FROZEN("PAT-1004"),

    /** 患者状态不允许该操作（409；如解冻非 FROZEN 档案、冻结 MERGED 档案） */
    PATIENT_STATE_NOT_ALLOWED("PAT-1005"),

    /** 在途就诊阻断合并（409；SPI 查询任一方存在在途就诊，M02 §5 主流程 2） */
    MERGE_BLOCKED_BY_ONGOING_VISIT("PAT-1006"),

    /** 合并记录不存在（404） */
    MERGE_RECORD_NOT_FOUND("PAT-1007"),

    /** 合并记录状态不允许该操作（409；如对 REVERSED 终态再拆分、对 COMPLETED 再审批） */
    MERGE_STATE_NOT_ALLOWED("PAT-1008"),

    /** 疑似重复记录不存在（404） */
    DUPLICATE_NOT_FOUND("PAT-1009"),

    /** 疑似重复记录已审核（409；PENDING 才可处置） */
    DUPLICATE_ALREADY_REVIEWED("PAT-1010"),

    /** 就诊卡不存在（404；卡面号无命中） */
    CARD_NOT_FOUND("PAT-1011"),

    /** 就诊卡状态不允许该操作（409；如挂失非 ACTIVE 卡、补卡非 LOST 卡） */
    CARD_STATE_NOT_ALLOWED("PAT-1012"),

    /** 一卡通账户不存在（404） */
    CARD_ACCOUNT_NOT_FOUND("PAT-1013"),

    /** 一卡通账户状态不允许该操作（409；如对 CLOSED 账户记账/冻结） */
    CARD_ACCOUNT_STATE_NOT_ALLOWED("PAT-1014"),

    /** 销户余额未清（409；CLOSED 前置校验失败） */
    CARD_ACCOUNT_BALANCE_NOT_SETTLED("PAT-1015"),

    /** 台账余额不足（409；PAY/REVERSE 记账后余额为负拒绝，串行化台账不超扣） */
    CARD_ACCOUNT_INSUFFICIENT_BALANCE("PAT-1016"),

    /** 健康档案项不存在（404） */
    HEALTH_ITEM_NOT_FOUND("PAT-1017"),

    /** 明文查阅未授权（403；无豁免角色或诊疗关系校验失败，双留痕后拒绝） */
    UNMASK_NOT_AUTHORIZED("PAT-1018"),

    /** 隐私授权记录不存在（404） */
    PRIVACY_AUTH_NOT_FOUND("PAT-1019"),

    /** visit_id 结构非法（400；CF-3 结构校验，供签发方自查与本模块入口校验） */
    VISIT_ID_MALFORMED("PAT-1020"),

    /** 脱敏规则不存在（404；rule_code 无命中，PUT /privacy-mask-rules/{ruleCode} 守卫） */
    PRIVACY_RULE_NOT_FOUND("PAT-1021"),

    /** 记账金额非法（400；金额必须为正数、方向由 txn_type 表达——审查 I5 记账契约收口） */
    CARD_TXN_AMOUNT_INVALID("PAT-1022");

    /** 错误码字符串，格式 {@code <模块助记>-<4位数字>} */
    private final String code;

    PatientErrorCode(String code) {
        this.code = code;
    }

    /**
     * 取业务错误码。
     *
     * @return 错误码字符串（如 PAT-1001），非空；经全局渲染输出至 ProblemDetail.properties.errorCode
     */
    @Override
    public String getCode() {
        return code;
    }
}
