package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 出入量小结入参（POST /api/v1/nursing/io-summaries，班次小结/24h 总结）。统计周期由服务端
 * 推导（SHIFT=病区班次定义当日窗；24H=当日 00:00–24:00，服务器时间 GC25），不设周期入参组件；
 * 同 (visit_id, summary_type, period_start, shift_key) 重复小结幂等返回既有行（NS-1016 仅
 * 兜底并发同周期冲突）。
 *
 * @param visitId    住院就诊号（I 型 14 位），必填；来源：操作者工作站当前患者
 * @param summaryType 小结类型 code（SHIFT/24H；非法值 NS-1019），必填；来源：录入面选择
 * @param shiftCode  班次 code（summaryType=SHIFT 必填，须在病区班次定义内 NS-1019；
 *                   summaryType=24H 不承载——服务端强制置空），可空；来源：录入面选择
 */
public record IoSummaryCreateRequest(
        @NotBlank(message = "visitId 不能为空") String visitId,
        @NotBlank(message = "summaryType 不能为空") String summaryType,
        String shiftCode) {}
