package com.fuyun.iot.controller;

import com.fuyun.iot.dto.CreateMetricRequest;
import com.fuyun.iot.enums.MetricCategory;
import com.fuyun.iot.service.IMetricDictService;
import com.fuyun.iot.vo.MetricDictVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MDC 术语字典端点（/api/v1/iot/metrics 两端点，FU-M14-02 字典自管不经 M01）：清单（GET，
 * 类别过滤可空）与登记（POST，201）。字典为模块专业词表，脱 M01 国标字典管辖。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用字典服务 + 编排响应；登记挂 WRITE 审计
 * （数据库写操作留痕口径与产品域写端点同构）。装配归 fuyun-app IotConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot/metrics")
public class MetricDictController {

    /** MDC 字典服务：两端点唯一业务出口 */
    private final IMetricDictService metricDictService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param metricDictService MDC 字典服务，非空
     */
    public MetricDictController(IMetricDictService metricDictService) {
        this.metricDictService = metricDictService;
    }

    /**
     * 字典清单（GET /api/v1/iot/metrics；纯读，metric_code 升序稳定输出）。
     *
     * @param category 类别过滤，可空（缺省全量）
     * @return 字典视图清单；200（空字典为空数组）
     */
    @GetMapping
    public List<MetricDictVO> list(@RequestParam(required = false) MetricCategory category) {
        return metricDictService.list(category);
    }

    /**
     * 字典登记（POST /api/v1/iot/metrics；WRITE 审计）。
     *
     * @param request 登记请求体（@Valid），非空
     * @return 落库后的字典视图；201
     * @throws com.fuyun.common.exception.BizException IOT-1005（409 metric_code 已存在）
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public MetricDictVO create(@Valid @RequestBody CreateMetricRequest request) {
        return metricDictService.create(request);
    }
}
