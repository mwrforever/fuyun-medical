package com.fuyun.integration.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.EventRegistryQuery;
import com.fuyun.integration.service.IEventRegistryService;
import com.fuyun.integration.vo.EventRegistryVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件契约台账读端点（GET /api/v1/integration/event-registry，M20 §7）。
 *
 * <p>只读面：契约登记由治理构件（发布方装配）自动完成，本端点供管理台查询「事件 × 生产方 ×
 * 订阅方 × 状态」矩阵；POST/DELETE /event-registry 不在本 PR 范围（登记已自动化、废止动作
 * 无 P0 消费方，见计划范围声明）。受既有 /api/v1/** 认证拦截。
 */
@RestController
@RequestMapping("/api/v1/integration/event-registry")
@Validated
public class EventRegistryController {

    private final IEventRegistryService eventRegistryService;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param eventRegistryService 事件契约台账服务，非空；注入接口类型（B.2-2）
     */
    public EventRegistryController(IEventRegistryService eventRegistryService) {
        this.eventRegistryService = eventRegistryService;
    }

    /**
     * 分页查询契约台账（按事件类型/生产方/状态过滤，类型名升序）。
     *
     * @param eventType      事件类型过滤，可空 = 不过滤
     * @param producerModule 生产模块过滤，可空 = 不过滤
     * @param status         契约状态过滤（ACTIVE/DEPRECATED），可空 = 不过滤
     * @param page           页码（0 基），缺省 0
     * @param size           单页条数（1-200），缺省 20
     * @return 分页出参（0 基页码）
     */
    @GetMapping
    public PageResult<EventRegistryVO> list(
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "producerModule", required = false) String producerModule,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return eventRegistryService.query(new EventRegistryQuery(eventType, producerModule, status, page, size));
    }
}
