package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.utils.SensitiveMasker;
import com.fuyun.patient.api.PatientDisplayName;
import com.fuyun.patient.api.PatientNameQuery;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.mapper.PatientMapper;
import java.util.Collection;
import java.util.List;

/**
 * 患者脱敏展示名查询实现（patient/api {@link PatientNameQuery}，PracticeCheckPort 同款
 * 「api 接口+provider 侧实现转调」范式）：patient 主表批量精确投影（in 查询，仅取 id+姓名两列，
 * A.4.3-14 禁全列查询与 N+1）+ 姓名原文不出模块的掩码收口（SensitiveMasker.maskName——保留
 * 姓氏、其余打星，M02 脱敏规则同源；空姓名/单字姓名原样返回）。高频读（队列快照 5s 轮询）故
 * 不打业务日志（禁高频日志刷屏）。线程安全：无状态单例。装配归 PatientWebConfig @Import。
 */
public class PatientNameQueryImpl implements PatientNameQuery {

    private final PatientMapper patientMapper;

    /**
     * 全参构造器（装配归 PatientWebConfig @Import，backend 宪法 B.1）。
     *
     * @param patientMapper 患者主表 mapper，非空；批量精确投影读取面
     */
    public PatientNameQueryImpl(PatientMapper patientMapper) {
        this.patientMapper = patientMapper;
    }

    /**
     * 批量查询患者脱敏展示名（掩码在 patient 侧收口——姓名原文不出本模块，跨模块契约红线）。
     *
     * @param patientIds 患者主索引集合，非 null；空集合零查询直接返回
     * @return 脱敏展示名视图列表；无命中返回空列表
     */
    @Override
    public List<PatientDisplayName> displayNamesOf(Collection<Long> patientIds) {
        // 空集合短路：队列无票/单票等小批量高频路径避免空 in 查询
        if (patientIds.isEmpty()) {
            return List.of();
        }
        // 数据库读操作：in 精确投影（仅 id+姓名两列，禁 SELECT * 出模块）
        List<Patient> rows = patientMapper.selectList(Wrappers.<Patient>lambdaQuery()
                .in(Patient::getPatientId, patientIds)
                .select(Patient::getPatientId, Patient::getName));
        // 掩码收口：展示名出网形态在此定型（调用方零脱敏逻辑，防原文经调用方日志/载荷泄漏）
        return rows.stream()
                .map(patient ->
                        new PatientDisplayName(patient.getPatientId(), SensitiveMasker.maskName(patient.getName())))
                .toList();
    }
}
