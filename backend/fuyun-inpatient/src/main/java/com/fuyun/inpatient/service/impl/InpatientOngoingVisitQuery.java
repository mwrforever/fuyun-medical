package com.fuyun.inpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.patient.api.OngoingVisitQuery;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「在途就诊查询」SPI 实现（patient/api/OngoingVisitQuery，M02 红线 4 依赖倒置的 M04 侧注册）：
 * 在院三态（REGISTERED 已登记待入科 / ADMITTED 在院 / DISCHARGE_REQUESTED 出院申请中）任一行
 * 存在即视为在途——随本实现注册，患者合并前置检查「命中即阻断」（契约冻结语义，
 * OutpatientOngoingVisitQuery/NursingOngoingVisitQuery 同款口径；DISCHARGED/CANCELLED 终态行
 * 不计入在途面）。装配归 InpatientWebConfig @Import（跨模块经 Spring 容器按接口类型收集，
 * 与 nursing 实现并存，任一命中即阻断）。
 * 线程安全：无状态单例。
 */
public class InpatientOngoingVisitQuery implements OngoingVisitQuery {

    private final InpatientVisitMapper visitMapper;

    /**
     * 全参构造器（装配归 InpatientWebConfig @Import）。
     *
     * @param visitMapper 住院就诊 mapper，非空；在院三态行计数查询
     */
    public InpatientOngoingVisitQuery(InpatientVisitMapper visitMapper) {
        this.visitMapper = visitMapper;
    }

    /**
     * 判定患者是否存在在途就诊（合并前置检查依据）：在院三态行存在即真。
     *
     * @param patientId 患者主索引（合并前主档与从档各查一次）；来源：M02 合并流程
     * @return true=存在在途就诊（阻断合并）；false=无在途就诊（放行）
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasOngoingVisit(long patientId) {
        Long ongoing = visitMapper.selectCount(Wrappers.<InpatientVisit>lambdaQuery()
                .eq(InpatientVisit::getPatientId, patientId)
                .in(
                        InpatientVisit::getStatus,
                        List.of(
                                VisitStatus.REGISTERED.getCode(),
                                VisitStatus.ADMITTED.getCode(),
                                VisitStatus.DISCHARGE_REQUESTED.getCode())));
        return ongoing != null && ongoing > 0;
    }
}
