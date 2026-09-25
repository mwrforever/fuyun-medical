package com.fuyun.pharmacy.api;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.ErrorCode;

/**
 * M06 药事域错误码枚举（PH-xxxx，A.3-4；前缀查证：M06 Spec 未定义，依惯例取 PH，
 * 全仓 grep 无冲突——2026-09-18 核验）。业务异常统一
 * {@code BizException(PharmacyErrorCode.X, HttpStatus, message)} 经全局渲染。
 */
public enum PharmacyErrorCode implements ErrorCode {

    /** 药品不存在（404；drug_code/id 无命中） */
    DRUG_NOT_FOUND("PH-1001"),
    /** 药品编码已存在（409；uk_drug_code 命中） */
    DRUG_CODE_EXISTS("PH-1002"),
    /** 药品状态不允许该操作（409；DISABLED 参与开方等状态机违例） */
    DRUG_STATE_NOT_ALLOWED("PH-1003"),
    /** 处方不存在（404；rx_no 无命中） */
    PRESCRIPTION_NOT_FOUND("PH-1004"),
    /** 处方状态不允许该操作（409；状态机违例：非待审态作废、终态再迁移等） */
    PRESCRIPTION_STATE_NOT_ALLOWED("PH-1005"),
    /** 处方明细非法（400；数量≤0/数量非数字串/药品未对照收费项目/途径集外等开方入参违例） */
    PRESCRIPTION_LINE_INVALID("PH-1006"),
    /** 就诊号格式非法（400；CF-3 定长 14 位校验失败，复用 patient VisitIdValidator 语义） */
    VISIT_ID_MALFORMED("PH-1007"),
    /** 调剂单不存在（404；dispense_no 无命中） */
    DISPENSE_NOT_FOUND("PH-1008"),
    /** 调剂单状态不允许该操作（409；状态机违例：非 CREATED 配药、非 PICKED 发药等） */
    DISPENSE_STATE_NOT_ALLOWED("PH-1009"),
    /** 批次可用量不足（409；配药锁定条件更新未命中——并发超发硬防线） */
    STOCK_INSUFFICIENT("PH-1010"),
    /** 调配核对同人（409；同一处方调配与核对不得同一人双签，Spec :226 分权） */
    DUAL_SIGN_CONFLICT("PH-1011"),
    /** 追溯码与发药记录不一致（409；退药实物核验防回流药，Spec §10 测试要点） */
    TRACE_CODE_MISMATCH("PH-1012"),
    /** 退药状态不允许（409；非 ISSUED 实物退、发药中明细全退场等分支违例） */
    RETURN_STATE_NOT_ALLOWED("PH-1013"),
    /** 已缴费处方拒作废（409；PENDING_DISPENSE 及之后须走退药/退费链，响应提示语引导） */
    RX_CANCEL_BLOCKED_AFTER_CHARGE("PH-1014"),
    /** 给药途径不在药品途径集（400；route_code ∉ drug.route_codes） */
    ROUTE_NOT_ALLOWED("PH-1015"),
    /**
     * 数值字段格式非法（400；退药数量/药品拆分比例等 DECIMAL string 入参与操作者工号解析非数字串
     * ——W-22⑦ 引入，禁 NumberFormatException 直穿 500 出契约外形态，SettlementServiceImpl 解析
     * 守卫同口径）
     */
    NUMERIC_FIELD_MALFORMED("PH-1016"),
    /**
     * 开方执业授权未过（403；处方权/抗菌药分级/麻精权纵深防御校验拒绝——裁决 9，Spec :226，文案含
     * 工号脱敏；PH-1016 已被 W-22⑦ 占用，本码接续顺延）
     */
    PRACTICE_NOT_ALLOWED("PH-1017"),
    /**
     * 取药凭证与处方归属不一致（409；扫码核对可选凭证核验拒绝——PR-5 裁决 8 凭证载体=settlementNo，
     * 经 billing SettlementQueryPort.settledUnder 反查该结算单下无该处方 SETTLED 费用行即拒；
     * PH-1017 已被 PRACTICE_NOT_ALLOWED 占用，本码接续顺延）
     */
    CREDENTIAL_MISMATCH("PH-1018"),
    /** 审方任务不存在（404；taskId 无命中——P2 PR-1 Task 12 审方薄切片接续顺延） */
    REVIEW_TASK_NOT_FOUND("PH-1019"),
    /** 审方任务状态不允许该操作（409；非 PENDING 决策、驳回缺意见、CAS 并发被抢等三态机违例） */
    REVIEW_TASK_STATE_NOT_ALLOWED("PH-1020"),
    /** 住院用药快照不存在（404；m04_order_no 无命中或任务关联快照缺失——数据不一致面） */
    MEDICATION_ORDER_NOT_FOUND("PH-1021");

    /** 码值（如 PH-1001），A.2-7 code↔enum 双向映射之 code 侧 */
    private final String code;

    PharmacyErrorCode(String code) {
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
