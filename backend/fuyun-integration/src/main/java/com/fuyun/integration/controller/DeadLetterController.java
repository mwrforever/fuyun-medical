package com.fuyun.integration.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.DeadLetterCloseRequest;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.service.IDeadLetterService;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 死信管理端点（GET /api/v1/integration/dead-letters[/{id}]，POST /{id}/replay、/{id}/close，M20 §7）。
 *
 * <p>受既有 /api/v1/** 认证拦截（SystemWebConfig，未认证 401）；职责边界：仅参数透传与响应编排
 * （宪法 B.1），禁业务逻辑、禁 @Transactional。分页契约 page 0 基 / size 1-200（A.3-6），越界由
 * 方法级校验渲染 400 ProblemDetail（Spring 6.2 内建 HandlerMethodValidationException 处理）。
 */
@RestController
@RequestMapping("/api/v1/integration/dead-letters")
@Validated
public class DeadLetterController {

    private final IDeadLetterService deadLetterService;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import，backend 宪法 B.1）。
     *
     * @param deadLetterService 死信管理服务，非空；注入接口类型（B.2-2）
     */
    public DeadLetterController(IDeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    /**
     * 分页查询死信（按状态/事件类型/事件 ID/来源队列过滤，首次死信时间倒序）。
     *
     * @param status      处理状态过滤，可空 = 不过滤
     * @param eventType   事件类型过滤，可空 = 不过滤
     * @param eventId     信封 eventId 过滤，可空 = 不过滤
     * @param sourceQueue 来源队列过滤，可空 = 不过滤
     * @param page        页码（0 基），非空，缺省 0
     * @param size        单页条数（1-200），非空，缺省 20
     * @return 分页出参（0 基页码）
     */
    @GetMapping
    public PageResult<DeadLetterVO> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "eventId", required = false) String eventId,
            @RequestParam(value = "sourceQueue", required = false) String sourceQueue,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return deadLetterService.query(new DeadLetterQuery(status, eventType, eventId, sourceQueue, page, size));
    }

    /**
     * 读取死信详情（含载荷全文，重放前人工核对）。
     *
     * @param id 死信 ID（路径参数），非空
     * @return 详情出参；不存在时由全局渲染器输出 404 ProblemDetail（INT-1001）
     */
    @GetMapping("/{id}")
    public DeadLetterDetailVO detail(@PathVariable("id") Long id) {
        return deadLetterService.detail(id);
    }

    /**
     * 重放死信（POST /dead-letters/{id}/replay，M20 §7）：原 eventId 重新入队，消费侧幂等防重复业务。
     *
     * @param id 死信 ID（路径参数），非空
     * @return 重放后详情（status=REPLAYED）；拒绝场景由全局渲染器输出 4xx/5xx ProblemDetail
     */
    @PostMapping("/{id}/replay")
    public DeadLetterDetailVO replay(@PathVariable("id") Long id) {
        return deadLetterService.replay(id);
    }

    /**
     * 关闭死信（POST /dead-letters/{id}/close，M20 §7）：必填原因，置终态 CLOSED。
     *
     * @param id      死信 ID（路径参数），非空
     * @param request 关闭请求，非空；handleNote 必填（JSR-303 校验失败由全局渲染器输出 400）
     * @return 关闭后详情（status=CLOSED）
     */
    @PostMapping("/{id}/close")
    public DeadLetterDetailVO close(@PathVariable("id") Long id, @Valid @RequestBody DeadLetterCloseRequest request) {
        return deadLetterService.close(id, request);
    }
}
