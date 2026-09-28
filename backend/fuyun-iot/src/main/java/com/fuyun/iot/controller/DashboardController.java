package com.fuyun.iot.controller;

import com.fuyun.iot.service.IDashboardService;
import com.fuyun.iot.vo.DashboardSummaryVO;
import com.fuyun.iot.vo.WardDeviceWallVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营大屏端点（/api/v1/iot/dashboard 两端点，FU-M14-13 数据面 + FU-M14-07 REST 兜底）：全院
 * 摘要（GET /summary，Redis 快照 TTL 5s read-through）与病区床位设备状态墙（GET /wards/{wardId}，
 * 绑定五元组+设备状态+最新值三面拼装）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅调用大屏数据面服务 + 编排响应；聚合/缓存/变更推送归服务层。
 * <b>REST 兜底注记（FU-M14-07 断连补齐）</b>：WS 断连重连后的增量拉取走既有
 * GET /api/v1/iot/telemetry/series（Task 10 时序查询）与 GET /api/v1/iot/alarms（Task 7 告警
 * 分页），本端点不重复承载。装配归 fuyun-app IotConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot/dashboard")
public class DashboardController {

    /** 大屏数据面服务：两端点唯一业务出口 */
    private final IDashboardService dashboardService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param dashboardService 大屏数据面服务，非空
     */
    public DashboardController(IDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * 全院运营摘要（GET /api/v1/iot/dashboard/summary；六项聚合，快照缓存 TTL 5s）。
     *
     * @return 全院摘要视图；200
     */
    @GetMapping("/summary")
    public DashboardSummaryVO summary() {
        return dashboardService.summary();
    }

    /**
     * 病区床位设备状态墙（GET /api/v1/iot/dashboard/wards/{wardId}；绑定五元组+设备状态+最新值）。
     *
     * @param wardId 病区 ID（路径变量）
     * @return 状态墙视图（绑定 id 升序）；200；病区无生效绑定为空条目清单
     */
    @GetMapping("/wards/{wardId}")
    public WardDeviceWallVO wardWall(@PathVariable Long wardId) {
        return dashboardService.wardWall(wardId);
    }
}
