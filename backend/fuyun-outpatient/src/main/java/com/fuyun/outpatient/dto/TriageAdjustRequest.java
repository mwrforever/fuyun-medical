package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 二次分诊/调级/转队列请求（POST /triage/adjust，M03 分诊台纠错面）：按 action 分流——
 * RE_TRIAGE 定医生（doctorId 必携）、LEVEL_ADJUST 调级（triageLevel 必携，票号不变——过号降级
 * 重排不改号 Spec :106）、QUEUE_TRANSFER 跨队列转接（targetQueue 必携，旧队放票新队建票）。
 * 调级重算为全量口径：priorityFactors 未携带则按无老幼残因子重算（分诊台提交完整因子集）。
 *
 * @param visitId         就诊号，非空白；来源：分诊台选中行
 * @param action          分诊动作词表值（RE_TRIAGE/LEVEL_ADJUST/QUEUE_TRANSFER），非空白；
 *                        来源：分诊台动作按钮（CHECK_IN 走 /triage/check-in 专用端点）
 * @param targetQueue     目标队列（=dept_code），转队列时必携；来源：诊区队列选择
 * @param doctorId        指派医生 id，二次分诊时必携；来源：医生选择
 * @param triageLevel     急诊分级（Ⅰ~Ⅳ=1~4），调级时必携；来源：分诊台判定
 * @param priorityFactors 老幼残优先级因子（全量口径，可空）；来源：分诊台人工判定
 * @param reason          动作理由（调级/转队列留痕，落 triage_record.reason 供质控回溯）；
 *                        LEVEL_ADJUST 服务层强制必携（空白拒绝），RE_TRIAGE/QUEUE_TRANSFER 无
 *                        理由语义不作 @NotBlank 强制——避免误伤无理由动作；来源：分诊台输入
 */
public record TriageAdjustRequest(
        @NotBlank(message = "visitId 不得为空白") String visitId,
        @NotBlank(message = "action 不得为空白") String action,
        String targetQueue,
        String doctorId,
        Integer triageLevel,
        List<String> priorityFactors,
        String reason) {}
