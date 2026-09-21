package com.fuyun.outpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.patient.api.CareRelationQuery;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「诊疗关系查询」SPI 实现（patient/api/CareRelationQuery，D-16 三态硬门禁第二道的 M03 侧注册）：
 * visit 表在途诊疗关系（patient_id+doctor_id 命中且状态 ∈ WAITING/IN_CONSULT/PENDING_FEE）命中即
 * true——随本实现注册，患者明文查阅 unmask 第二道校验自动从「无实现跳过 warn」收紧为「无豁免且无
 * 诊疗关系即 403」（契约冻结语义，CareRelationQuery javadoc；OngoingVisitQuery 冻结语义对称）。
 * REGISTERED 未报到不计入（医患未实际接触），终态/声明态不计入。装配归 OutpatientWebConfig
 * @Import（跨模块经 Spring 容器按接口类型收集）。线程安全：无状态单例。
 */
public class OutpatientCareRelationQuery implements CareRelationQuery {

    /** 在途诊疗关系状态全集（候诊/就诊中/待缴费三态——医患接触期口径，brief 冻结） */
    private static final List<VisitStatus> CARE_STATUSES =
            List.of(VisitStatus.WAITING, VisitStatus.IN_CONSULT, VisitStatus.PENDING_FEE);

    private final VisitMapper visitMapper;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import）。
     *
     * @param visitMapper 就诊记录 mapper，非空；诊疗关系命中计数查询
     */
    public OutpatientCareRelationQuery(VisitMapper visitMapper) {
        this.visitMapper = visitMapper;
    }

    /**
     * 判定操作者与患者是否存在在途诊疗关系（明文查阅第二道门禁依据）。
     *
     * @param patientId  患者主索引；来源：unmask 请求体
     * @param operatorId 操作者标识（登录名/工号=visit.doctor_id 同一口径）；来源：OperatorContextHolder
     * @return true=存在在途诊疗关系（放行并留痕）；false=无关系（403）
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasCareRelation(long patientId, String operatorId) {
        // 数据库读操作：患者×医生×在途三态命中计数（第二道门禁判定依据）
        Long hits = visitMapper.selectCount(Wrappers.<Visit>lambdaQuery()
                .eq(Visit::getPatientId, patientId)
                .eq(Visit::getDoctorId, operatorId)
                .in(Visit::getStatus, CARE_STATUSES));
        return hits != null && hits > 0;
    }
}
