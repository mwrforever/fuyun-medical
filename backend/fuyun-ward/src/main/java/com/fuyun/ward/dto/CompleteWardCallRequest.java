package com.fuyun.ward.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 呼叫完成请求（POST /api/v1/ward/ward-calls/{callNo}/complete 请求体）：结果摘要强制
 * （brief 冻结「COMPLETED 必填 result_summary」，空值借承 WD-1005 拒绝），列宽防线 500。
 *
 * @param resultSummary 处理结果摘要，非空；来源：操作者填写
 */
public record CompleteWardCallRequest(
        @NotBlank(message = "resultSummary 不能为空") @Size(max = 500, message = "resultSummary 最长 500 字符")
        String resultSummary) {}
