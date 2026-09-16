package com.fuyun.patient.service;

import com.fuyun.patient.dto.PatientCreateRequest;
import com.fuyun.patient.vo.PatientMatchCheckVO;

/**
 * 患者建档主用例（FU-M02-01）：匹配预检 → 归一或新建 → 标识挂接 → 知情同意落痕 →
 * 疑似重复生成 → patient.created 事件（事务内应用事件，AFTER_COMMIT 出 MQ）。
 */
public interface PatientRegistrationService {

    /**
     * 建档（写事务）。
     *
     * @param request 建档请求，非空（Bean Validation 已在 controller 层校验）；来源：窗口/自助/线上/住院登记
     * @return 建档结果（复用预检结论载体：AUTO_MATCH 时 candidatePatientId 即归一既有档 id，
     *         新建时 outcome 保留 SUSPECT/NO_MATCH 且 candidatePatientId=新档 id）；非空
     */
    PatientMatchCheckVO register(PatientCreateRequest request);
}
