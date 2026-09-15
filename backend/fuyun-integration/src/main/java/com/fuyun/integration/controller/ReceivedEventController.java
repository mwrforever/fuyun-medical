package com.fuyun.integration.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.ReceivedEventQuery;
import com.fuyun.integration.service.IReceivedEventQueryService;
import com.fuyun.integration.vo.ReceivedEventVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消费台账读端点（GET /api/v1/integration/received-events，M20 §7 + FU-M20-06 事件查询台）。
 *
 * <p>受既有 /api/v1/** 认证拦截；职责边界：仅参数透传与响应编排（宪法 B.1）。时间参数取 ISO-8601
 * （{@code 2026-09-15T01:02:03Z}），闭区间语义（ge/le）；非法 UUID 或时间格式由 Spring 参数绑定
 * 失败渲染 400 ProblemDetail。
 */
@RestController
@RequestMapping("/api/v1/integration/received-events")
@Validated
public class ReceivedEventController {

    private final IReceivedEventQueryService receivedEventQueryService;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param receivedEventQueryService 消费台账查询服务，非空；注入接口类型（B.2-2）
     */
    public ReceivedEventController(IReceivedEventQueryService receivedEventQueryService) {
        this.receivedEventQueryService = receivedEventQueryService;
    }

    /**
     * 分页查询消费记录（按事件类型/事件 ID/消费者/状态/接收时间窗检索）。
     *
     * @param eventType      事件类型过滤，可空 = 不过滤
     * @param eventId        信封 eventId 过滤（UUID），可空 = 不过滤
     * @param consumerModule 消费者模块标识过滤，可空 = 不过滤
     * @param status         消费状态过滤（PROCESSED/FAILED），可空 = 不过滤
     * @param receivedFrom   接收时间下界（含，ISO-8601），可空 = 不限
     * @param receivedTo     接收时间上界（含，ISO-8601），可空 = 不限
     * @param page           页码（0 基），缺省 0
     * @param size           单页条数（1-200），缺省 20
     * @return 分页出参（0 基页码）
     */
    @GetMapping
    public PageResult<ReceivedEventVO> list(
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "eventId", required = false) UUID eventId,
            @RequestParam(value = "consumerModule", required = false) String consumerModule,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "receivedFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime receivedFrom,
            @RequestParam(value = "receivedTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime receivedTo,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return receivedEventQueryService.query(new ReceivedEventQuery(
                eventType, eventId, consumerModule, status, receivedFrom, receivedTo, page, size));
    }
}
