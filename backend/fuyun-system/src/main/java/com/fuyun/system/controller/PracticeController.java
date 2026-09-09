package com.fuyun.system.controller;

import com.fuyun.system.dto.PracticeCheckRequest;
import com.fuyun.system.service.IPracticeService;
import com.fuyun.system.vo.PracticeCheckResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执业授权校验端点（POST /api/v1/system/practice/check，M01 §7 路径原样，BRIEF-PR3-01 §3.3）。
 *
 * <p>受 401 认证拦截（权限点 /api/v1/system/practice/check 已随 V303 种子登记，API 权限强制
 * 403 属 P1）。P0 为骨架端点：真实校验随 P1 执业授权库表启用。查询型端点 P0 不审计
 * （敏感查询留痕 P1，简报 §3.3 注解落点口径）。职责边界：仅 @Valid 校验 + 调用 service +
 * 编排响应，禁业务逻辑与事务（宪法 B.1/A.1-8）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system/practice")
public class PracticeController {

    private final IPracticeService practiceService;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param practiceService 执业授权校验服务，非空；注入接口类型（B.2-2）
     */
    public PracticeController(IPracticeService practiceService) {
        this.practiceService = practiceService;
    }

    /**
     * 执业授权校验（P0 骨架）：入参校验后透传服务层，返回固定"未通过"占位语义。
     *
     * @param request 校验请求，非空；employeeId/grantType 非空由 JSR-303 校验
     * @return 校验响应（入参回显 + passed=false），非空
     */
    @PostMapping("/check")
    public PracticeCheckResponse check(@Valid @RequestBody PracticeCheckRequest request) {
        return practiceService.check(request);
    }
}
