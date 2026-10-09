package com.fuyun.ops.controller;

import com.fuyun.ops.service.IOpsWorkbenchService;
import com.fuyun.ops.vo.WorkbenchEventsVO;
import com.fuyun.ops.vo.WorkbenchOverviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营工作台聚合端点（/api/v1/ops/workbench/**，批次 2 册 2 首切片）：总览快照与事件流两读
 * 端点，供工作站首页（web-workstation HomeView）真数据消费。分析只读域（M19 红线 1）——
 * 零写端点零审计 WRITE 面；查询/统计面不加审计读注记（W-47 统计 GET 面既有口径）。
 * controller 禁业务逻辑与事务（宪法 A.1-8），只编排响应。
 */
@Tag(name = "M19 运营工作台", description = "总览快照（指标六格+14 日趋势+候诊表）与事件流（五源）")
@RestController
@RequestMapping("/api/v1/ops/workbench")
@RequiredArgsConstructor
@Validated
public class OpsWorkbenchController {

    private final IOpsWorkbenchService workbenchService;

    /**
     * 工作台总览快照（Redis read-through TTL 5s）：指标带六格 + 14 日趋势 + 按科室候诊表。
     *
     * @return 总览快照（无业务数据返回零值/空清单视图，不造数）
     */
    @Operation(summary = "工作台总览快照（指标六格+14 日趋势+候诊表，TTL 5s 缓存）", operationId = "getOpsWorkbenchOverview")
    @GetMapping("/overview")
    public WorkbenchOverviewVO overview() {
        return workbenchService.overview();
    }

    /**
     * 工作台事件流（五源）：三 STOMP 端点主题订阅指引 + 两 HTTP 轮询派生待办 + 危急值缺位
     * 降级段（criticalValues 恒空数组 + criticalValueDegraded=true 判别标志，前端按降级文案
     * 渲染——M07 检验域落地后回填）。
     *
     * @return 事件流快照（无待办返回空清单）
     */
    @Operation(summary = "工作台事件流（三 STOMP 主题指引+两轮询源待办+危急值降级段）", operationId = "getOpsWorkbenchEvents")
    @GetMapping("/events")
    public WorkbenchEventsVO events() {
        return workbenchService.events();
    }
}
