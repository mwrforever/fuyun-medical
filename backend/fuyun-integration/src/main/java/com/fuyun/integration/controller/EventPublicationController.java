package com.fuyun.integration.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.EventPublicationQuery;
import com.fuyun.integration.service.IEventPublicationQueryService;
import com.fuyun.integration.vo.EventPublicationVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投递台账读端点（GET /api/v1/integration/event-publications）：Modulith 事件注册表只读投影。
 *
 * <p>受既有 /api/v1/** 认证拦截；职责边界：仅参数透传与响应编排（宪法 B.1）。status 仅接受
 * COMPLETED/INCOMPLETE（@Pattern 声明式校验，非法值渲染 400）。
 */
@RestController
@RequestMapping("/api/v1/integration/event-publications")
@Validated
public class EventPublicationController {

    private final IEventPublicationQueryService eventPublicationQueryService;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param eventPublicationQueryService 投递台账查询服务，非空；注入接口类型（B.2-2）
     */
    public EventPublicationController(IEventPublicationQueryService eventPublicationQueryService) {
        this.eventPublicationQueryService = eventPublicationQueryService;
    }

    /**
     * 分页查询投递记录（排查未完成投递：status=INCOMPLETE）。
     *
     * @param eventType     事件类型全限定名过滤，可空 = 不过滤
     * @param status        完成态过滤（COMPLETED/INCOMPLETE），可空 = 不过滤
     * @param publishedFrom 发布时间下界（含，ISO-8601），可空 = 不限
     * @param publishedTo   发布时间上界（含，ISO-8601），可空 = 不限
     * @param page          页码（0 基），缺省 0
     * @param size          单页条数（1-200），缺省 20
     * @return 分页出参（0 基页码）
     */
    @GetMapping
    public PageResult<EventPublicationVO> list(
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "status", required = false)
                    @Pattern(regexp = "COMPLETED|INCOMPLETE", message = "status 仅支持 COMPLETED 或 INCOMPLETE")
                    String status,
            @RequestParam(value = "publishedFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime publishedFrom,
            @RequestParam(value = "publishedTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime publishedTo,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return eventPublicationQueryService.query(
                new EventPublicationQuery(eventType, status, publishedFrom, publishedTo, page, size));
    }
}
