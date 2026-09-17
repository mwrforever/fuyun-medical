package com.fuyun.billing.vo;

import com.fuyun.billing.entity.InsuranceCallLog;

/**
 * 医保调用留痕出参（GET /insurance/call-logs；组件清单为 Task 19 前端唯一依据，禁改名改序）：
 * 枚举出 code 字符串、id/结算单 id Long 包装出网（统一契约口径）经 Jackson→string；摘要列原样
 * 透传（落库侧已脱敏+钳 512，出网不二次加工）。
 *
 * @param id             留痕行 id
 * @param txnCode        基线版交易码（2001 门诊登记/2101 费用上传/2102 预结算/2104 撤销，模拟通道同码）
 * @param visitId        CF-3 就诊号（可空）
 * @param settlementId   结算单 id（可空）
 * @param requestDigest  请求摘要（脱敏，禁完整明文）
 * @param responseDigest 应答摘要/回执原文引用摘要（钳 512，可空）
 * @param centerSerialNo 中心流水号（可空）
 * @param resultCode     中心结果码（模拟=0000 成功，可空）
 * @param resultMsg      中心结果消息（可空）
 * @param durationMs     调用耗时（毫秒，可空）
 * @param status         调用状态 INIT/SENT/SUCCESS/FAILED/TIMEOUT/COMPENSATED/WAIVED
 * @param compensateNote 补偿/核销结论（可空）
 * @param traceId        全链路 traceId（可空）
 */
public record InsuranceCallLogVO(
        Long id,
        String txnCode,
        String visitId,
        Long settlementId,
        String requestDigest,
        String responseDigest,
        String centerSerialNo,
        String resultCode,
        String resultMsg,
        Long durationMs,
        String status,
        String compensateNote,
        String traceId) {

    /**
     * 实体 → 出参静态工厂（controller 出网边界专用，禁实体直出）。
     *
     * @param row 留痕行实体，非空；来源：留痕分页查询结果
     * @return 出参 VO，非空；枚举列转 code 字符串
     */
    public static InsuranceCallLogVO from(InsuranceCallLog row) {
        return new InsuranceCallLogVO(
                row.getId(),
                row.getTxnCode(),
                row.getVisitId(),
                row.getSettlementId(),
                row.getRequestDigest(),
                row.getResponseDigest(),
                row.getCenterSerialNo(),
                row.getResultCode(),
                row.getResultMsg(),
                row.getDurationMs(),
                row.getStatus() == null ? null : row.getStatus().getCode(),
                row.getCompensateNote(),
                row.getTraceId());
    }
}
