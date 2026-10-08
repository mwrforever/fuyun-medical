package com.fuyun.nursing.service.impl;

import com.fuyun.nursing.api.NursingStatsPort;
import com.fuyun.nursing.api.NursingWorkloadStats;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;

/**
 * 护理工作量统计端口实现（NursingStatsPort 承载，M05 → M19 统计接口位；BillingAccountQueryPortImpl
 * 同款形态）：在院人数计数（病区患者投影在册行数）。纯只读，零事务零写面。
 *
 * <p>在院口径：nursing_ward_patient 纯事件投影在册行（deleted=0 逻辑删面自动排除出院行，W-34
 * 退役后单写面=四路事件消费）。无状态单例；装配归 NursingWebConfig @Import；JaCoCo
 * nursing.service.impl 核心包 LINE=1.00 成员。
 */
public class NursingStatsPortImpl implements NursingStatsPort {

    /** 病区患者投影 mapper：在册行计数取数面 */
    private final NursingWardPatientMapper wardPatientMapper;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import，backend 宪法 B.1）。
     *
     * @param wardPatientMapper 病区患者投影 mapper，非空；在册行计数取数面
     */
    public NursingStatsPortImpl(NursingWardPatientMapper wardPatientMapper) {
        this.wardPatientMapper = wardPatientMapper;
    }

    /**
     * 在院人数计数（只读）：投影表全量在册行数（逻辑删行由 @TableLogic 自动排除）。
     * 数据库读操作（COUNT 聚合）。
     *
     * @return 工作量统计快照，非空；无在册行返回零值视图
     */
    @Override
    public NursingWorkloadStats inHospitalStats() {
        // 数据库读操作：在册投影行全量计数
        Long count = wardPatientMapper.selectCount(null);
        return new NursingWorkloadStats(count == null ? 0L : count);
    }
}
