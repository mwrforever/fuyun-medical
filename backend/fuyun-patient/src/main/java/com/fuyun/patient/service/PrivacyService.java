package com.fuyun.patient.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.patient.dto.UnmaskRequest;
import com.fuyun.patient.vo.PrivacyAccessLogVO;
import com.fuyun.patient.vo.UnmaskVO;

/**
 * 隐私明文查阅与留痕服务（FU-M02-06 展示侧出口）：明文查阅双留痕（M01 审计 + privacy_access_log）。
 */
public interface PrivacyService {

    /**
     * 明文查阅（独立 API：角色豁免校验 → 解密取值 → 查阅台账落痕 → 返回明文）。
     *
     * @param request 查阅请求（字段词表/purpose 必填），非空
     * @return 明文值集，非空
     * @throws com.fuyun.common.exception.BizException PAT-1018（403 无豁免角色——不落查阅台账，
     *                                                  留痕由 @AuditLog FAIL 审计行承担）/ PAT-1001
     */
    UnmaskVO unmask(UnmaskRequest request);

    /**
     * 查阅台账分页（等保审计主检索）。
     *
     * @param patientId 患者过滤（可空=全量）
     * @param page      0 基页码
     * @param size      1-200
     * @return 台账分页，非空
     */
    PageResult<PrivacyAccessLogVO> listAccessLogs(Long patientId, int page, int size);
}
