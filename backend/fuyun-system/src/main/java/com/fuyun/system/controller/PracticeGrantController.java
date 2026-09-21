package com.fuyun.system.controller;

import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import com.fuyun.system.dto.PracticeGrantCreateRequest;
import com.fuyun.system.dto.PracticeWithdrawRequest;
import com.fuyun.system.service.IPracticeService;
import com.fuyun.system.vo.PracticeGrantVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执业授权管理端点（FU-M01-04，/api/v1/system/practice/grants）：授权登记（重复 EFFECTIVE
 * 409 SYS-1022）、停权（SUSPENDED + practice.changed 广播）、按员工清单（过期读侧派生 EXPIRED
 * 展示）。写端点 @AuditLog WRITE 留痕；查询端点不审计（同 PracticeController 口径）。
 * 职责边界：仅 @Valid 校验 + 调用 service + 编排响应，禁业务逻辑与事务（宪法 B.1/A.1-8）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/system/practice")
public class PracticeGrantController {

    private final IPracticeService practiceService;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param practiceService 执业授权校验与管理服务，非空；注入接口类型（B.2-2）
     */
    public PracticeGrantController(IPracticeService practiceService) {
        this.practiceService = practiceService;
    }

    /**
     * 授权登记：落 EFFECTIVE 行，事务提交后广播 practice.changed。
     *
     * @param request 登记请求，非空；grantType 词表/employeeId/validFrom 非空由 JSR-303 校验
     * @return 新登记授权行 ID（雪花 ID 以 JSON 字符串输出）
     * @throws com.fuyun.common.exception.BizException SYS-1022/409 重复生效行冲突
     */
    @PostMapping("/grants")
    @AuditLog(actionType = AuditActionType.WRITE)
    public long grant(@Valid @RequestBody PracticeGrantCreateRequest request) {
        return practiceService.grant(request);
    }

    /**
     * 授权停权：仅 EFFECTIVE 可停（CAS），成功后广播 practice.changed（SUSPENDED）。
     *
     * @param id      授权行主键（路径参数），非空正整数
     * @param request 停权请求体，非空；reason 非空由 JSR-303 校验（留痕依据）
     * @throws com.fuyun.common.exception.BizException SYS-1021/404 行不存在或已非生效态
     */
    @PostMapping("/grants/{id}/withdraw")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void withdraw(@PathVariable("id") @Positive long id, @Valid @RequestBody PracticeWithdrawRequest request) {
        practiceService.withdraw(id, request.reason());
    }

    /**
     * 按员工查询授权清单：valid_to 已过的 EFFECTIVE 行读侧派生 EXPIRED 展示，不回写库。
     *
     * @param employeeId 员工 ID（查询参数），非空正整数
     * @return 授权展示清单（id 降序），无授权返回空清单
     */
    @GetMapping("/grants")
    public List<PracticeGrantVO> listByEmployee(@RequestParam("employeeId") @Positive long employeeId) {
        return practiceService.listByEmployee(employeeId);
    }
}
