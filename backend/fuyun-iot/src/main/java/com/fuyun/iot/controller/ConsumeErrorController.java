package com.fuyun.iot.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.AbandonConsumeErrorRequest;
import com.fuyun.iot.dto.ConsumeErrorQueryRequest;
import com.fuyun.iot.service.IConsumeErrorLogService;
import com.fuyun.iot.vo.ConsumeErrorVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消费错误管理端点（/api/v1/iot/consume-errors 三端点，FU-M14-01 管理界面"可查可重放"面，
 * V401 P0 遗留端点义务补齐）：分页查询、重放（重新入解析管道）、放弃（终态，原因强制）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用错误日志服务 + 编排响应；状态机 CAS 与
 * 分派归服务层。重放/放弃为敏感管理操作挂 WRITE 审计（审计切面记录入参携带放弃原因摘要）。
 * 装配归 fuyun-app IotConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot/consume-errors")
public class ConsumeErrorController {

    /** 消费错误日志服务：三端点唯一业务出口 */
    private final IConsumeErrorLogService consumeErrorLogService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param consumeErrorLogService 消费错误日志服务，非空
     */
    public ConsumeErrorController(IConsumeErrorLogService consumeErrorLogService) {
        this.consumeErrorLogService = consumeErrorLogService;
    }

    /**
     * 消费错误分页（GET /api/v1/iot/consume-errors；队列名/处置状态过滤，page 0 基）。
     *
     * @param request 分页查询请求（查询参数绑定，@Valid），非空
     * @return 分页出参；200
     */
    @GetMapping
    public PageResult<ConsumeErrorVO> page(@Valid ConsumeErrorQueryRequest request) {
        return consumeErrorLogService.page(request);
    }

    /**
     * 消费错误重放（POST /api/v1/iot/consume-errors/{errorId}/replay；重新入解析管道，WRITE 审计）。
     *
     * @param errorId 错误行 ID（路径变量），非空
     * @return 重放后的错误日志视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1020（404）或 IOT-1021（409）
     */
    @PostMapping("/{errorId}/replay")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ConsumeErrorVO replay(@PathVariable Long errorId) {
        return consumeErrorLogService.replay(errorId);
    }

    /**
     * 消费错误放弃（POST /api/v1/iot/consume-errors/{errorId}/abandon；终态迁移原因强制，
     * WRITE 审计——请求体原因即审计留痕摘要）。
     *
     * @param errorId 错误行 ID（路径变量），非空
     * @param request 放弃请求体（@Valid，原因强制），非空
     * @return 放弃后的错误日志视图；200
     * @throws com.fuyun.common.exception.BizException IOT-1020（404）或 IOT-1021（409/400）
     */
    @PostMapping("/{errorId}/abandon")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ConsumeErrorVO abandon(@PathVariable Long errorId, @Valid @RequestBody AbandonConsumeErrorRequest request) {
        return consumeErrorLogService.abandon(errorId, request);
    }
}
