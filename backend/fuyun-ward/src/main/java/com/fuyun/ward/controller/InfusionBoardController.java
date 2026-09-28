package com.fuyun.ward.controller;

import com.fuyun.ward.service.IInfusionBoardService;
import com.fuyun.ward.vo.InfusionBoardVO;
import com.fuyun.ward.vo.InfusionHistoryVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 输液看板端点（/api/v1/ward 两端点，FU-M16 输液监控编排面）：病区看板（余量/滴速聚合与三档
 * 告警映射）与设备历史追溯（遥测曲线面）。5ml 红档呼叫落行走 iot.alarm.triggered 消费链，不经
 * 本端点；告警实时面复用 iot WS 推送主题（后端零改动，前端 Task 16 消费）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅调用看板服务 + 编排响应（纯读视图零写面）；装配归
 * fuyun-app WardConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/ward")
public class InfusionBoardController {

    /** 输液看板服务：两端点唯一业务出口 */
    private final IInfusionBoardService infusionBoardService;

    /**
     * 全参构造器（装配归 WardWebConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param infusionBoardService 输液看板服务，非空
     */
    public InfusionBoardController(IInfusionBoardService infusionBoardService) {
        this.infusionBoardService = infusionBoardService;
    }

    /**
     * 病区输液看板（GET /api/v1/ward/infusion-board/{wardId}）：活跃输液呼叫圈定设备，余量/滴速
     * 最新值聚合与三档映射（15ml 黄/10ml 橙/5ml 红）。
     *
     * @param wardId 病区 ID（路径变量）
     * @return 看板视图；200
     */
    @GetMapping("/infusion-board/{wardId}")
    public InfusionBoardVO board(@PathVariable Long wardId) {
        return infusionBoardService.board(wardId);
    }

    /**
     * 输液历史追溯（GET /api/v1/ward/infusion-history/{deviceId}）：余量/滴速双曲线；告警聚合
     * 缺位注记随出参透出（iot/api 无告警查询端口——实测结论，P1 接口面补齐）。
     *
     * @param deviceId IoTDA 设备标识（路径变量）
     * @return 历史视图；200
     */
    @GetMapping("/infusion-history/{deviceId}")
    public InfusionHistoryVO history(@PathVariable String deviceId) {
        return infusionBoardService.history(deviceId);
    }
}
