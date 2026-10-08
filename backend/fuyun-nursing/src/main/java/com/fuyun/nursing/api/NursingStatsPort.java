package com.fuyun.nursing.api;

/**
 * 护理工作量统计只读端口（M05 → M19 统计接口位，api 包唯一出口；NursingTaskLinkagePort
 * 同款先例）：批次 2 册 2 工作台聚合的护理取数面——在院人数计数，纯只读零状态迁移零业务写
 * （M19 红线 1 分析不回写）。
 */
public interface NursingStatsPort {

    /**
     * 护理在院计数聚合。
     *
     * @return 工作量统计快照，非空；无在册行返回零值视图（不造数）
     */
    NursingWorkloadStats inHospitalStats();
}
