package com.fuyun.nursing.vo;

import java.util.List;

/**
 * 体温单月页出参（GET /api/v1/nursing/temperature-charts）：月页定位信息 + 三类条目分组
 * （vitals/specialEvents/dailyValues），各段按 entryTime 升序（服务端排序，前端直渲染）。
 * 月页未创建时返回空出参（三段恒空清单，非 null）。
 *
 * @param visitId      住院就诊号
 * @param chartMonth   住院月页（yyyy-MM）
 * @param pageId       月页 id（月页未创建为 null）
 * @param pageStatus   页状态（ChartPageStatus code；月页未创建为 null）
 * @param vitals       体征引用条目段（VITAL）
 * @param specialEvents 特殊事件条目段（SPECIAL_EVENT）
 * @param dailyValues  日行值条目段（DAILY_VALUE）
 */
public record TemperatureChartVO(
        String visitId,
        String chartMonth,
        Long pageId,
        String pageStatus,
        List<ChartEntryVO> vitals,
        List<ChartEntryVO> specialEvents,
        List<ChartEntryVO> dailyValues) {}
