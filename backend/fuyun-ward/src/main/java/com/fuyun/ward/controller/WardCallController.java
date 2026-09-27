package com.fuyun.ward.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import com.fuyun.ward.dto.CompleteWardCallRequest;
import com.fuyun.ward.dto.CreateWardCallRequest;
import com.fuyun.ward.dto.WardCallQueryRequest;
import com.fuyun.ward.service.IWardCallService;
import com.fuyun.ward.vo.WardCallRouteVO;
import com.fuyun.ward.vo.WardCallVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 呼叫端点（/api/v1/ward/ward-calls 九端点，FU-M16-01 呼叫信令面）：手工创建、列表/详情
 * （读时惰性升级承载面）、六动作子路径（answer/progress/complete/transfer/route/cancel，
 * POST 形态照 iot 先例）。设备源呼叫（IOT）经 iot.alarm.triggered 消费落行，不经本入口。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用呼叫服务 + 编排响应；状态机 CAS 与
 * 升级惰性判定归服务层；写操作挂 WRITE 审计。装配归 fuyun-app WardConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/ward/ward-calls")
public class WardCallController {

    /** 呼叫服务：九端点唯一业务出口 */
    private final IWardCallService callService;

    /**
     * 全参构造器（装配归 WardWebConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param callService 呼叫服务，非空
     */
    public WardCallController(IWardCallService callService) {
        this.callService = callService;
    }

    /**
     * 手工创建呼叫（POST /api/v1/ward/ward-calls；WRITE 审计；同床位旧呼叫合并取消）。
     *
     * @param request 创建请求体（@Valid），非空
     * @return 创建后的呼叫视图；200
     */
    @PostMapping
    @AuditLog(actionType = AuditActionType.WRITE)
    public WardCallVO create(@Valid @RequestBody CreateWardCallRequest request) {
        return callService.create(request);
    }

    /**
     * 呼叫分页（GET /api/v1/ward/ward-calls；读时惰性升级判定承载面——当前页超时未升级行
     * 递增 escalation_count 后回读实态）。
     *
     * @param request 分页查询请求（查询参数绑定，@Valid），非空
     * @return 分页出参；200
     */
    @GetMapping
    public PageResult<WardCallVO> page(@Valid WardCallQueryRequest request) {
        return callService.page(request);
    }

    /**
     * 呼叫详情（GET /api/v1/ward/ward-calls/{callNo}；读时惰性升级判定承载面）。
     *
     * @param callNo 呼叫业务号（路径变量）
     * @return 呼叫视图；200
     * @throws com.fuyun.common.exception.BizException WD-1001（404）
     */
    @GetMapping("/{callNo}")
    public WardCallVO get(@PathVariable String callNo) {
        return callService.get(callNo);
    }

    /**
     * 应答（POST /api/v1/ward/ward-calls/{callNo}/answer；CREATED/TRANSFERRED → ANSWERED；WRITE 审计）。
     *
     * @param callNo 呼叫业务号（路径变量）
     * @return 应答后视图；200
     * @throws com.fuyun.common.exception.BizException WD-1001（404）或 WD-1002（409）
     */
    @PostMapping("/{callNo}/answer")
    @AuditLog(actionType = AuditActionType.WRITE)
    public WardCallVO answer(@PathVariable String callNo) {
        return callService.answer(callNo);
    }

    /**
     * 进入处理中（POST /api/v1/ward/ward-calls/{callNo}/progress；ANSWERED → IN_PROGRESS；WRITE 审计）。
     *
     * @param callNo 呼叫业务号（路径变量）
     * @return 处理后视图；200
     * @throws com.fuyun.common.exception.BizException WD-1001（404）或 WD-1002（409）
     */
    @PostMapping("/{callNo}/progress")
    @AuditLog(actionType = AuditActionType.WRITE)
    public WardCallVO progress(@PathVariable String callNo) {
        return callService.progress(callNo);
    }

    /**
     * 完成（POST /api/v1/ward/ward-calls/{callNo}/complete；result_summary 强制；WRITE 审计）。
     *
     * @param callNo  呼叫业务号（路径变量）
     * @param request 完成请求体（@Valid），非空
     * @return 完成后视图；200
     * @throws com.fuyun.common.exception.BizException WD-1001（404）/WD-1002（409）/WD-1005（400 借承）
     */
    @PostMapping("/{callNo}/complete")
    @AuditLog(actionType = AuditActionType.WRITE)
    public WardCallVO complete(@PathVariable String callNo, @Valid @RequestBody CompleteWardCallRequest request) {
        return callService.complete(callNo, request);
    }

    /**
     * 转接（POST /api/v1/ward/ward-calls/{callNo}/transfer；CREATED/ANSWERED → TRANSFERRED；WRITE 审计）。
     *
     * @param callNo 呼叫业务号（路径变量）
     * @return 转接后视图；200
     * @throws com.fuyun.common.exception.BizException WD-1001（404）或 WD-1002（409）
     */
    @PostMapping("/{callNo}/transfer")
    @AuditLog(actionType = AuditActionType.WRITE)
    public WardCallVO transfer(@PathVariable String callNo) {
        return callService.transfer(callNo);
    }

    /**
     * 转接触发（POST /api/v1/ward/ward-calls/{callNo}/route；规则驱动转接：CAS 转接 + 目标链
     * 解析一并返回，无规则 WD-1003 整体回滚；WRITE 审计）。
     *
     * @param callNo 呼叫业务号（路径变量）
     * @return 路由解析视图（目标链 + 任务转换开关快照）；200
     * @throws com.fuyun.common.exception.BizException WD-1001（404）/WD-1002（409）/WD-1003（409）
     */
    @PostMapping("/{callNo}/route")
    @AuditLog(actionType = AuditActionType.WRITE)
    public WardCallRouteVO route(@PathVariable String callNo) {
        return callService.route(callNo);
    }

    /**
     * 取消（POST /api/v1/ward/ward-calls/{callNo}/cancel；CREATED/TRANSFERRED → CANCELLED；WRITE 审计）。
     *
     * @param callNo 呼叫业务号（路径变量）
     * @return 取消后视图；200
     * @throws com.fuyun.common.exception.BizException WD-1001（404）或 WD-1002（409）
     */
    @PostMapping("/{callNo}/cancel")
    @AuditLog(actionType = AuditActionType.WRITE)
    public WardCallVO cancel(@PathVariable String callNo) {
        return callService.cancel(callNo);
    }
}
