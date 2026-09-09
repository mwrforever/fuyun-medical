package com.fuyun.system.service;

import com.fuyun.system.dto.PracticeCheckRequest;
import com.fuyun.system.vo.PracticeCheckResponse;

/**
 * 执业授权校验服务（M01 §7 practice/check 用例，BRIEF-PR3-01 §3.3）。
 *
 * <p>P0 为骨架实现：入参回显 + 固定语义 passed=false（执业授权库表 practice_grant 属 P0 明确
 * 不做范围，简报 §0）。P1 替换内部实现（practice_grant 表 + EFFECTIVE 状态校验 + 30 天到期通知），
 * 本接口与响应契约不变。查询型用例不开事务（P0 无任何写路径）。
 */
public interface IPracticeService {

    /**
     * 执业授权校验：P0 骨架返回"未通过 + 占位说明"。
     *
     * @param request 校验请求，非空；employeeId/grantType 由 JSR-303 校验非空
     * @return 校验响应（入参回显 + passed=false + 占位 reason），非空
     */
    PracticeCheckResponse check(PracticeCheckRequest request);
}
