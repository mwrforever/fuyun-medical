package com.fuyun.nursing.vo;

import java.time.LocalDate;
import java.util.Map;

/**
 * 不良事件分类统计出参（GET /api/v1/nursing/stats/adverse-events）：统计日窗口内按
 * 类别/病区/等级（分级+等级双维度）/时段（班次）聚合计数 + I/II 级时限合规面，供 M19
 * 护理质量指标消费（消费方缺位登记——本出参先行）。非惩罚红线：纯计数聚合，零个人身份
 * 面（无上报人/处置人维度）。
 *
 * <p>词表维度（类别八值/分级四值/等级五值/班次三值）零填充——稳定契约形态（前端图表
 * 免判空）；病区维度动态键（仅观测病区入键）。
 *
 * @param date             统计日（occurred_at 当日窗口，北京钟面）
 * @param total            统计窗口内事件总数
 * @param byCategory       类别聚合计数（八类零填充：MEDICATION_ERROR/FALL/PRESSURE_ULCER/
 *                         TUBE_SLIP/BLOOD_TRANFUSION/DEVICE/FACILITY/OTHER）
 * @param bySeverityClass  严重度分级聚合计数（I/II/III/IV 零填充）
 * @param bySeverityGrade  严重度等级聚合计数（A~E 零填充）
 * @param byWard           病区聚合计数（动态键：仅观测病区入键）
 * @param byShift          时段聚合计数（班次零填充：DAY 08-16/EVENING 16-24/NIGHT 0-8，
 *                         与执行工作台班次映射同源）
 * @param deadlineTotal    I/II 级行数（预置 24h 上报时限的行集——时限合规分母）
 * @param deadlineMetCount 时限达成行数（deadline_met=true——按时上报率分子，流程改进面）
 */
public record AdverseEventStatsVO(
        LocalDate date,
        long total,
        Map<String, Long> byCategory,
        Map<String, Long> bySeverityClass,
        Map<String, Long> bySeverityGrade,
        Map<String, Long> byWard,
        Map<String, Long> byShift,
        long deadlineTotal,
        long deadlineMetCount) {}
