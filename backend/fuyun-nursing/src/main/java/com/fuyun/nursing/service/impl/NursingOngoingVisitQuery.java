package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.patient.api.OngoingVisitQuery;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「在途就诊查询」SPI 实现（patient/api/OngoingVisitQuery，M02 红线 4 依赖倒置的 M05 侧注册）：
 * 病区患者投影存在在册行（deleted=0——W-34 后在册语义由逻辑删单独承载，discharged 事件
 * 逻辑删即出在册面）即视为在途——随本实现注册，患者合并前置检查「命中即阻断」（契约冻结
 * 语义，OutpatientOngoingVisitQuery:19 同款口径；逻辑删行不计入在途面）。
 * 装配归 NursingWebConfig @Import（跨模块经 Spring 容器按接口类型收集）。
 * 线程安全：无状态单例。
 */
public class NursingOngoingVisitQuery implements OngoingVisitQuery {

    private final NursingWardPatientMapper wardPatientMapper;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param wardPatientMapper 病区患者投影 mapper，非空；在册行计数查询
     */
    public NursingOngoingVisitQuery(NursingWardPatientMapper wardPatientMapper) {
        this.wardPatientMapper = wardPatientMapper;
    }

    /**
     * 判定患者是否存在在途就诊（合并前置检查依据）：病区患者投影在册行存在即真
     * （逻辑删由 @TableLogic 自动过滤——出院终清即出在册面）。
     *
     * @param patientId 患者主索引（合并前主档与从档各查一次）；来源：M02 合并流程
     * @return true=存在在途就诊（阻断合并）；false=无在途就诊（放行）
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasOngoingVisit(long patientId) {
        Long inWard = wardPatientMapper.selectCount(
                Wrappers.<NursingWardPatient>lambdaQuery().eq(NursingWardPatient::getPatientId, patientId));
        return inWard != null && inWard > 0;
    }
}
