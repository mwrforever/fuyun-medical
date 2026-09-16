package com.fuyun.patient.controller;

import com.fuyun.patient.dto.CardBindRequest;
import com.fuyun.patient.dto.CardIssueRequest;
import com.fuyun.patient.dto.CardReplaceRequest;
import com.fuyun.patient.service.VisitCardService;
import com.fuyun.patient.vo.CardVO;
import com.fuyun.system.api.AuditLog;
import com.fuyun.system.enums.AuditActionType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 就诊卡端点（M02 Spec §7 FU-M02-04，A.3-1 动作+资源子路径收敛定稿）：
 * POST /cards/issue|bind|loss/{cardNo}|replace|unbind/{cardNo} + GET /cards/{cardNo}。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：状态机守卫与事务边界在 service impl 方法级；
 * 动作型端点挂 WRITE 审计（@AuditLog 注解 + AuditLogAspect 上下文内拦截——窗口办理的
 * 证件核验留痕口径），查询端点只读不审计。
 */
@RestController
@RequestMapping("/api/v1/patient")
public class CardController {

    private final VisitCardService visitCardService;

    /**
     * 构造器注入（A.1-7），装配归 PatientWebConfig @Import。
     *
     * @param visitCardService 就诊卡生命周期服务（发卡/绑定/挂失/补卡/解绑状态机），非空
     */
    public CardController(VisitCardService visitCardService) {
        this.visitCardService = visitCardService;
    }

    /**
     * 发卡并绑定档案（POST /cards/issue；WRITE 审计；一卡通启用时联动开户）。
     *
     * @param request 发卡请求（@Valid）；来源：窗口建档/挂号流程
     * @return 卡出参；200
     * @throws com.fuyun.common.exception.BizException PAT-1012（409 卡号已登记）
     */
    @PostMapping("/cards/issue")
    @AuditLog(actionType = AuditActionType.WRITE)
    public CardVO issue(@Valid @RequestBody CardIssueRequest request) {
        return visitCardService.issue(request);
    }

    /**
     * 绑定既有无主卡到档案（POST /cards/bind；WRITE 审计）。
     *
     * @param request 绑定请求（@Valid）；来源：窗口核验证件后办理
     * @return 卡出参；200
     * @throws com.fuyun.common.exception.BizException PAT-1011（404）/ PAT-1012（409 卡已挂接他档）
     */
    @PostMapping("/cards/bind")
    @AuditLog(actionType = AuditActionType.WRITE)
    public CardVO bind(@Valid @RequestBody CardBindRequest request) {
        return visitCardService.bind(request);
    }

    /**
     * 挂失（POST /cards/loss/{cardNo}，ACTIVE→LOST 解析立即失效；账户联动冻结；WRITE 审计）。
     *
     * @param cardNo 卡面号（路径变量）
     * @return 204 无体
     * @throws com.fuyun.common.exception.BizException PAT-1011（404）/ PAT-1012（409 非 ACTIVE）
     */
    @PostMapping("/cards/loss/{cardNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void loss(@PathVariable String cardNo) {
        visitCardService.loss(cardNo);
    }

    /**
     * 补卡（POST /cards/replace，旧卡 REPLACED 终态 + 新卡发号绑定同档案；WRITE 审计）。
     *
     * @param request 补卡请求（@Valid，旧卡号+新卡号）；来源：窗口挂失后受理
     * @return 新卡出参；200
     * @throws com.fuyun.common.exception.BizException PAT-1011（404）/ PAT-1012（409 旧卡非 LOST）/
     *                                                 PAT-1002（409 新卡号已存在）
     */
    @PostMapping("/cards/replace")
    @AuditLog(actionType = AuditActionType.WRITE)
    public CardVO replace(@Valid @RequestBody CardReplaceRequest request) {
        return visitCardService.replace(request);
    }

    /**
     * 解绑（POST /cards/unbind/{cardNo}，ACTIVE→DISABLED 终态，账户不销户；WRITE 审计）。
     *
     * @param cardNo 卡面号（路径变量）
     * @return 204 无体
     * @throws com.fuyun.common.exception.BizException PAT-1011（404）/ PAT-1012（409 非 ACTIVE）
     */
    @PostMapping("/cards/unbind/{cardNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void unbind(@PathVariable String cardNo) {
        visitCardService.unbind(cardNo);
    }

    /**
     * 卡详情（GET /cards/{cardNo}，只读不审计）。
     *
     * @param cardNo 卡面号（路径变量）
     * @return 卡出参；200
     * @throws com.fuyun.common.exception.BizException PAT-1011（404 卡号无命中）
     */
    @GetMapping("/cards/{cardNo}")
    public CardVO detail(@PathVariable String cardNo) {
        return visitCardService.getByCardNo(cardNo);
    }
}
