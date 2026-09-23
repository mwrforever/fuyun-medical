package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 交接班单生成入参（POST /api/v1/nursing/handovers/generate）：交班护士发起，系统按本班
 * 业务数据自动汇总患者摘要/SBAR 初稿/待续事项（Spec :138 流程 5），生成即盖章交班签名。
 *
 * @param wardId    病区编码，必填；来源：操作者工作站当前病区
 * @param shiftCode 班次 code（取病区班次定义，V801 种子 DAY/EVENING/NIGHT），必填；来源：操作者选择
 */
public record HandoverGenerateRequest(
        @NotBlank(message = "病区编码不能为空") String wardId,
        @NotBlank(message = "班次编码不能为空") String shiftCode) {}
