package com.fuyun.ward.controller;

import com.fuyun.ward.service.IVitalSignBoardService;
import com.fuyun.ward.vo.VitalBoardVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 体征看板端点（GET /api/v1/ward/vital-board/{wardId}，FU-M16-02 编排视图面）：presence 指标锚
 * 与采集质量注记。在床/离床逐设备状态与落卡权威归 M05（PR-3，GC17⑥——本 PR 仅视图骨架）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅调用看板服务 + 编排响应（纯读视图零写面）；装配归
 * fuyun-app WardConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/ward")
public class VitalSignBoardController {

    /** 体征看板服务：端点唯一业务出口 */
    private final IVitalSignBoardService vitalSignBoardService;

    /**
     * 全参构造器（装配归 WardWebConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param vitalSignBoardService 体征看板服务，非空
     */
    public VitalSignBoardController(IVitalSignBoardService vitalSignBoardService) {
        this.vitalSignBoardService = vitalSignBoardService;
    }

    /**
     * 病区体征看板（GET /api/v1/ward/vital-board/{wardId}）：presence 指标锚 + anomaly 注记清单
     * （deviceId 维度——病区过滤待绑定面 PR-3 闭合）。
     *
     * @param wardId 病区 ID（路径变量）
     * @return 看板视图；200
     */
    @GetMapping("/vital-board/{wardId}")
    public VitalBoardVO board(@PathVariable Long wardId) {
        return vitalSignBoardService.board(wardId);
    }
}
