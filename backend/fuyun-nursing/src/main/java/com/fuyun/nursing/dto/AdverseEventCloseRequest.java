package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 不良事件关闭入参（POST /api/v1/nursing/adverse-events/{no}/close）：HANDLING→CLOSED
 * 迁移载体，RCA 根因分析与整改措施随关闭落库（流程改进面——非惩罚红线出口）。
 *
 * @param rcaNote          根因分析记录（≤2000 可空——按需补录，缺省保留行原值），可空；来源：关闭表单
 * @param correctiveAction 整改措施（≤2000 可空——按需补录，缺省保留行原值），可空；来源：关闭表单
 * @param closedBy         关闭人员工 ID（必填——经 updated_by 审计列承载留痕），非空；来源：关闭表单
 */
public record AdverseEventCloseRequest(
        @Size(max = 2000, message = "根因分析超长（≤2000）") String rcaNote,

        @Size(max = 2000, message = "整改措施超长（≤2000）") String correctiveAction,

        @NotNull(message = "关闭人必填（closedBy）") Long closedBy) {}
