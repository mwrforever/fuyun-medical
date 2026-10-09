package com.fuyun.nursing.api;

/**
 * 护理工作量统计只读快照（NursingStatsPort 返回载体，M05 → M19 统计接口位契约）：在院人数
 * 计数，纯计数聚合零患者级明细（M19 红线 1 分析不回写；红线 5 汇总天然脱敏）。
 *
 * @param inHospitalCount 当前在院人数（nursing_ward_patient 事件投影在册行数，逻辑删面自动
 *                        排除出院行），非空（无在册行为 0）
 */
public record NursingWorkloadStats(long inHospitalCount) {}
