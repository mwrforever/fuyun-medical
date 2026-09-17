package com.fuyun.patient.service;

import com.fuyun.patient.dto.PatientMatchCheckRequest;
import com.fuyun.patient.vo.PatientMatchCheckVO;

/**
 * EMPI 分层匹配引擎（M02 Spec §3.1 分层组合，FU-M02-02 核心）：强标识精确 → 属性比对 → 弱标识评分。
 */
public interface PatientMatchingService {

    /**
     * 建档前匹配预检（只读，不产生任何写副作用）。
     *
     * @param request 预检入参（姓名/性别必填，证件/手机号可空），非空；来源：建档表单或建档请求
     * @return 匹配结论（outcome/candidatePatientId/score/matchedRules），非空
     */
    PatientMatchCheckVO preCheck(PatientMatchCheckRequest request);
}
