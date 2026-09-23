package com.fuyun.nursing.api;

import com.fuyun.common.exception.ErrorCode;

/**
 * M05 护理管理模块错误码枚举（NS-xxxx，backend 宪法 A.3-4）。
 *
 * <p>落 api 包为宪法 B.1 明文（错误码枚举属对外契约）；实现 common {@link ErrorCode} 契约，
 * 全项目编码唯一（NS- 前缀 2026-09-22 全仓 grep 核验无其他占用），NS-1001 起连续无重号
 * （NS-1017/NS-1018 预留不分配；NS-1019 承接入参格式语义位，形态照 OutpatientErrorCode 先例）。
 * 业务异常抛 {@code BizException(NursingErrorCode.XXX, HttpStatus, message)}，由全局渲染器
 * 输出 RFC 9457 ProblemDetail（properties.errorCode/traceId），禁止「全 200 + 错误码」。
 */
public enum NursingErrorCode implements ErrorCode {

    /** 病区患者不存在（404；visitId 在区行定位失败） */
    WARD_PATIENT_NOT_FOUND("NS-1001"),

    /** 床位占用冲突（409；同床位已有在区患者/床日分配唯一键冲突） */
    BED_OCCUPIED("NS-1002"),

    /** 就诊标识不合法（400；visitId 结构校验未过） */
    VISIT_ID_INVALID("NS-1003"),

    /** 患者被拦截（409；档案冻结或合并中禁止入区登记） */
    PATIENT_BLOCKED("NS-1004"),

    /** 体征超生理极限（400；任一指标越生理极限值拒收） */
    VITAL_OUT_OF_RANGE("NS-1005"),

    /** 体征状态不允许该操作（409；待审/已转正等状态违例） */
    VITAL_STATE_NOT_ALLOWED("NS-1006"),

    /** 护理记录已提交锁定（409；SUBMITTED 后禁改，修订走 revise 链） */
    RECORD_LOCKED("NS-1007"),

    /** 护理记录修订链不合法（409；仅 SUBMITTED 行可发起修订） */
    RECORD_REVISE_NOT_ALLOWED("NS-1008"),

    /** 评估量表类型不支持（400；CUSTOM 自定义引擎归 P2） */
    SCALE_TYPE_UNSUPPORTED("NS-1009"),

    /** 评估单不完整（400；条目缺失或总分不可判级） */
    ASSESSMENT_INCOMPLETE("NS-1010"),

    /** 护理任务状态不允许该操作（409；完成/取消等状态违例） */
    TASK_STATE_NOT_ALLOWED("NS-1011"),

    /** 护理任务不存在（404；taskNo 定位失败） */
    TASK_NOT_FOUND("NS-1012"),

    /** 交接班状态不允许该操作（409；确认/关闭等状态违例） */
    HANDOVER_STATE_NOT_ALLOWED("NS-1013"),

    /** 交接班不存在（404；handoverId 定位失败） */
    HANDOVER_NOT_FOUND("NS-1014"),

    /** 体征复核状态不允许该操作（409；仅 PENDING_REVIEW 可转正/驳回） */
    VITAL_REVIEW_STATE_NOT_ALLOWED("NS-1015"),

    /** 资源冲突（409；唯一键冲突/未知病区/归属不符/补录超窗——与 NS-1019 入参格式严格分开） */
    CONFLICT("NS-1016"),

    /** 入参显式格式校验失败（400；W-22⑦「禁裸 parse」先例，格式违例显式拒绝） */
    PARAM_FORMAT_INVALID("NS-1019");

    /** 错误码字符串，格式 {@code <模块助记>-<4位数字>} */
    private final String code;

    NursingErrorCode(String code) {
        this.code = code;
    }

    /**
     * 取业务错误码。
     *
     * @return 错误码字符串（如 NS-1001），非空；经全局渲染输出至 ProblemDetail.properties.errorCode
     */
    @Override
    public String getCode() {
        return code;
    }
}
