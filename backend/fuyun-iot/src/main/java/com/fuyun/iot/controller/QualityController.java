package com.fuyun.iot.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.QualityStatQueryRequest;
import com.fuyun.iot.service.IQualityService;
import com.fuyun.iot.vo.DataQualityStatVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据质量端点（/api/v1/iot/quality 两端点，FU-M14-11 质量看板数据面）：质量日统计与设备利用率
 * （同表同惰性重算语义，出参侧重不同）。统计落库随查询惰性触发（批量定时调度随 P3 归 Task 18）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用质量服务 + 编排响应；重算公式与断流判定
 * 归服务层。装配归 fuyun-app IotConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot/quality")
public class QualityController {

    /** 质量监控服务：两端点唯一业务出口 */
    private final IQualityService qualityService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param qualityService 质量监控服务，非空
     */
    public QualityController(IQualityService qualityService) {
        this.qualityService = qualityService;
    }

    /**
     * 质量日统计（GET /api/v1/iot/quality/stats；deviceId 显式指定时触发该设备统计惰性重算）。
     *
     * @param request 查询请求（查询参数绑定，@Valid），非空
     * @return 统计分页出参（含利用率折算）；200
     * @throws com.fuyun.common.exception.BizException IOT-1006（404；deviceId 显式指定但设备不存在）
     */
    @GetMapping("/stats")
    public PageResult<DataQualityStatVO> stats(@Valid QualityStatQueryRequest request) {
        return qualityService.qualityStats(request);
    }

    /**
     * 设备利用率（GET /api/v1/iot/quality/device-usage；有数据时长占比，FU-M15-05 数据源）。
     *
     * @param request 查询请求（查询参数绑定，@Valid），非空
     * @return 统计分页出参（usageRate 为核心字段）；200
     * @throws com.fuyun.common.exception.BizException IOT-1006（404；deviceId 显式指定但设备不存在）
     */
    @GetMapping("/device-usage")
    public PageResult<DataQualityStatVO> deviceUsage(@Valid QualityStatQueryRequest request) {
        return qualityService.deviceUsage(request);
    }
}
