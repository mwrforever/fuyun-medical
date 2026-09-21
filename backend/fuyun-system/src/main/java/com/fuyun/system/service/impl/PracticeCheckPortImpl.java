package com.fuyun.system.service.impl;

import com.fuyun.system.api.PracticeCheckPort;
import com.fuyun.system.api.PracticeCheckResult;
import com.fuyun.system.dto.PracticeCheckRequest;
import com.fuyun.system.service.IPracticeService;
import com.fuyun.system.vo.PracticeCheckResponse;
import org.springframework.transaction.annotation.Transactional;

/**
 * 执业授权校验端口实现（api 面 → IPracticeService 转调，跨模块进程内唯一消费通道）：REST 契约
 * POST /practice/check 与 api 端口同源同一校验实现（checkTime 缺省取服务端当前时刻），passed/reason
 * 原样透传不二次包装（调用方 OP-1017（M03）/PH-1017（M06）语义不受影响）。线程安全：无状态单例。装配归
 * SystemWebConfig @Import；端口转调行覆盖由 PracticeCheckPortImplTest 承载（system impl 包
 * LINE=1.00）。
 */
public class PracticeCheckPortImpl implements PracticeCheckPort {

    private final IPracticeService practiceService;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param practiceService 执业授权校验服务，非空；EFFECTIVE 授权+有效期真实校验
     */
    public PracticeCheckPortImpl(IPracticeService practiceService) {
        this.practiceService = practiceService;
    }

    /**
     * 执行授权校验（接口 javadoc 契约）：转调 IPracticeService.check 并投影 passed/reason。
     *
     * @param employeeId 员工 ID，非空
     * @param grantType  授权类型词表值，非空
     * @return 校验结果（passed+reason 原样透传），非空
     */
    @Override
    @Transactional(readOnly = true)
    public PracticeCheckResult check(long employeeId, String grantType) {
        PracticeCheckResponse response = practiceService.check(new PracticeCheckRequest(employeeId, grantType, null));
        return new PracticeCheckResult(response.passed(), response.reason());
    }
}
