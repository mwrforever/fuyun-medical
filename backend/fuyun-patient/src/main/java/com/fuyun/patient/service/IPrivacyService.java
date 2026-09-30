package com.fuyun.patient.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.patient.dto.PrivacyAccessLogQuery;
import com.fuyun.patient.dto.UnmaskRequest;
import com.fuyun.patient.vo.PrivacyAccessLogVO;
import com.fuyun.patient.vo.UnmaskVO;

/**
 * 隐私明文查阅与留痕服务（FU-M02-06 展示侧出口）：明文查阅双留痕（M01 审计 + privacy_access_log）。
 */
public interface IPrivacyService {

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
     * 查阅台账分页（等保审计主检索，GET /privacy-access-logs 端点用例）：检索条件编排（单页条数
     * 越界收敛 1-200）归本方法承载，controller 仅组装原始请求参数。
     *
     * @param query 台账检索条件（patientId 可空=全量；page 0 基原样透传；size 原始请求值，收敛 1-200），非空
     * @return 台账分页（page/size 按收敛后口径回显），非空
     */
    PageResult<PrivacyAccessLogVO> listAccessLogs(PrivacyAccessLogQuery query);
}
