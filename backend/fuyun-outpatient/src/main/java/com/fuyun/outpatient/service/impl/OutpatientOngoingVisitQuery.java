package com.fuyun.outpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.patient.api.OngoingVisitQuery;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「在途就诊查询」SPI 实现（patient/api/OngoingVisitQuery，M02 红线 4 依赖倒置的 M03 侧注册）：
 * visit 表在途态（REGISTERED/WAITING/IN_CONSULT/PENDING_FEE）命中即 true——随本实现注册，患者合并
 * 前置检查自动从「无实现放行」收紧为「命中即阻断」（契约冻结语义，OngoingVisitQuery javadoc）。
 * 声明态（IN_EXECUTION/PENDING_MEDICATION/NO_SHOW）与终态不计入在途面。装配归 OutpatientWebConfig
 * @Import（跨模块经 Spring 容器按接口类型收集，MergeRecordServiceImpl 注入 List&lt;OngoingVisitQuery&gt;）。
 * 线程安全：无状态单例。
 */
public class OutpatientOngoingVisitQuery implements OngoingVisitQuery {

    /** 在途态全集（visit 主状态机非终态非声明态四态，红线 5 合法迁移对涉及的进行中状态） */
    private static final List<VisitStatus> ONGOING_STATUSES =
            List.of(VisitStatus.REGISTERED, VisitStatus.WAITING, VisitStatus.IN_CONSULT, VisitStatus.PENDING_FEE);

    private final VisitMapper visitMapper;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import）。
     *
     * @param visitMapper 就诊记录 mapper，非空；在途态计数查询
     */
    public OutpatientOngoingVisitQuery(VisitMapper visitMapper) {
        this.visitMapper = visitMapper;
    }

    /**
     * 判定患者是否存在在途就诊（合并前置检查依据）。
     *
     * @param patientId 患者主索引（合并前主档与从档各查一次）；来源：M02 合并流程
     * @return true=存在在途就诊（阻断合并）；false=无在途就诊（放行）
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasOngoingVisit(long patientId) {
        Long ongoing = visitMapper.selectCount(Wrappers.<Visit>lambdaQuery()
                .eq(Visit::getPatientId, patientId)
                .in(Visit::getStatus, ONGOING_STATUSES));
        return ongoing != null && ongoing > 0;
    }
}
