package com.fuyun.system.service.impl;

import com.fuyun.system.dto.PracticeCheckRequest;
import com.fuyun.system.service.IPracticeService;
import com.fuyun.system.vo.PracticeCheckResponse;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;

/**
 * 执业授权校验服务实现（P0 骨架，BRIEF-PR3-01 §3.3）。
 *
 * <p>P0 语义：入参回显 + passed=false + 固定占位 reason（无任何数据访问——practice_grant 表
 * 属 P0 明确不做范围，禁止为骨架引入死表访问）。P1 替换内部实现：practice_grant 表按
 * （employeeId, grantType）查 EFFECTIVE 授权并校验有效期，响应契约不变。
 * 线程安全：无状态单例。装配归 SystemWebConfig @Import。
 */
@Slf4j
public class PracticeServiceImpl implements IPracticeService {

    /** P0 占位结论文案：与 P1 真实校验启用前的对外解释口径一致 */
    private static final String SKELETON_REASON = "执业授权库表随 P1 交付后启用真实校验";

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     */
    public PracticeServiceImpl() {}

    /**
     * 执业授权校验（P0 骨架）：checkTime 缺省取服务端当前时刻，返回固定"未通过"语义。
     *
     * @param request 校验请求，非空
     * @return 校验响应（入参回显 + passed=false + 占位 reason），非空
     */
    @Override
    public PracticeCheckResponse check(PracticeCheckRequest request) {
        OffsetDateTime checkTime = request.checkTime() != null ? request.checkTime() : OffsetDateTime.now();
        log.info(
                "执业授权校验（P0 骨架占位）：employeeId={}，grantType={}，checkTime={}",
                request.employeeId(),
                request.grantType(),
                checkTime);
        // P1 扩展点：practice_grant 表 EFFECTIVE 授权查询 + 有效期校验 + 30 天到期通知，替换本返回值
        return new PracticeCheckResponse(
                String.valueOf(request.employeeId()), request.grantType(), checkTime, false, SKELETON_REASON);
    }
}
