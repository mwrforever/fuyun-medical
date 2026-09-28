package com.fuyun.iot.service;

import com.fuyun.iot.dto.CreateMetricRequest;
import com.fuyun.iot.enums.MetricCategory;
import com.fuyun.iot.vo.MetricDictVO;
import java.util.List;

/**
 * MDC 术语字典服务接口（P2 PR-2 Task 4）：模块自管专业字典（不经 M01）的清单查询与登记。
 */
public interface IMetricDictService {

    /**
     * 字典清单（GET /api/v1/iot/metrics）：按类别过滤可空，metric_code 升序稳定输出。
     *
     * @param category 类别过滤，可空（null 不过滤）
     * @return 字典视图清单，非空（空字典为空清单）
     */
    List<MetricDictVO> list(MetricCategory category);

    /**
     * 字典登记（POST /api/v1/iot/metrics）：metric_code 自然键唯一，重复登记拒绝。
     *
     * @param request 登记请求体（已过 @Valid），非空
     * @return 落库后的字典视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1005（409 metric_code 已存在）
     */
    MetricDictVO create(CreateMetricRequest request);
}
