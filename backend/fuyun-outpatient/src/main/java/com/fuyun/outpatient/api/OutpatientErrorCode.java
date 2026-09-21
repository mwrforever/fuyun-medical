package com.fuyun.outpatient.api;

import com.fuyun.common.exception.ErrorCode;

/**
 * M03 门诊服务模块错误码枚举（OP-xxxx，backend 宪法 A.3-4）。
 *
 * <p>落 api 包为宪法 B.1 明文（错误码枚举属对外契约）；实现 common {@link ErrorCode} 契约，
 * 全项目编码唯一（OP- 前缀 2026-09-20 全仓 grep 核验无其他占用），OP-1001 起连续无重号。
 * 业务异常抛 {@code BizException(OutpatientErrorCode.XXX, HttpStatus, message)}，由全局渲染器
 * 输出 RFC 9457 ProblemDetail（properties.errorCode/traceId），禁止"全 200 + 错误码"。
 */
public enum OutpatientErrorCode implements ErrorCode {

    /** 就诊记录不存在（404；visitId 定位失败） */
    VISIT_NOT_FOUND("OP-1001"),

    /** 号源池不存在（404；poolId 定位失败） */
    POOL_NOT_FOUND("OP-1002"),

    /** 号源不足（409；Redis 扣减与池行条件更新双重拒发） */
    POOL_EXHAUSTED("OP-1003"),

    /** 排班状态不允许该操作（409；停诊/恢复/生成等状态违例） */
    SCHEDULE_STATE_NOT_ALLOWED("OP-1004"),

    /** 重复预约（409；同患者同日同科限约冲突） */
    DUPLICATE_APPOINTMENT("OP-1005"),

    /** 爽约限约期内（409；患者处于信用限约窗口禁预约） */
    APPT_RESTRICTED("OP-1006"),

    /** 患者被拦截（409；冻结/合并中状态禁止业务动作） */
    PATIENT_BLOCKED("OP-1007"),

    /** 支付时限已过（409；预约占位超支付截止） */
    PAY_DEADLINE_PASSED("OP-1008"),

    /** 预约单状态不允许该操作（409；取消/改期/支付等状态违例） */
    APPOINTMENT_STATE_NOT_ALLOWED("OP-1009"),

    /** 线上退号时限外（409；超 onlineCancelBeforeDays 转窗口办理） */
    CANCEL_WINDOW_CLOSED("OP-1010"),

    /** 就诊状态不允许该操作（409；FINISHED/CANCELLED 后拒绝开单/缴费/执行动作） */
    VISIT_STATE_NOT_ALLOWED("OP-1011"),

    /** 候诊票据不存在（404；ticketId 定位失败） */
    TICKET_NOT_FOUND("OP-1012"),

    /** 候诊票据状态不允许该操作（409；过号/叫号等状态违例） */
    TICKET_STATE_NOT_ALLOWED("OP-1013"),

    /** 申请单不存在（404；orderNo 定位失败） */
    ORDER_NOT_FOUND("OP-1014"),

    /** 申请单状态不允许该操作（409；作废/开单等状态违例） */
    ORDER_STATE_NOT_ALLOWED("OP-1015"),

    /** 诊毕校验未过（409；在途单据未收敛禁止诊毕） */
    FINISH_CHECK_FAILED("OP-1016"),

    /** 开单执业授权未过（403；PracticeCheckPort 校验拒绝） */
    PRACTICE_CHECK_FAILED("OP-1017"),

    /** 离院去向代码词表外（400；disposition 非国标 1~7/9） */
    DISPOSITION_INVALID("OP-1018"),

    /** 入参显式格式校验失败（400；W-22⑦「禁裸 parse」先例，格式违例显式拒绝） */
    PARAM_FORMAT_INVALID("OP-1019");

    /** 错误码字符串，格式 {@code <模块助记>-<4位数字>} */
    private final String code;

    OutpatientErrorCode(String code) {
        this.code = code;
    }

    /**
     * 取业务错误码。
     *
     * @return 错误码字符串（如 OP-1001），非空；经全局渲染输出至 ProblemDetail.properties.errorCode
     */
    @Override
    public String getCode() {
        return code;
    }
}
