package com.fuyun.outpatient.dto;

import java.time.LocalDate;

/**
 * 排班清单查询对象（GET /api/v1/outpatient/schedules，backend 宪法 A.7-1 参数对象化：
 * 过滤条件+分页参数整体传参）。三个过滤条件均可空（空=不过滤该维度）。
 *
 * @param deptCode 开诊科室编码，可空（null=全部科室）；来源：工作台科室筛选
 * @param dateFrom 排班日期下界（含），可空；来源：工作台日期范围
 * @param dateTo   排班日期上界（含），可空；来源：工作台日期范围
 * @param page     页码（0 基，A.3-6）；来源：分页控件
 * @param size     单页条数；来源：分页控件
 */
public record SchedulePageQuery(String deptCode, LocalDate dateFrom, LocalDate dateTo, int page, int size) {}
