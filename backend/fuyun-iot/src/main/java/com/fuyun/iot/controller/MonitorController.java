package com.fuyun.iot.controller;

import com.fuyun.iot.service.IQualityService;
import com.fuyun.iot.vo.ConsumerStatVO;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消费监控端点（/api/v1/iot/monitor/consumer-lag，FU-M14-01 积压观测面读取口）：消费积压快照
 * 采样落表随查询惰性触发后返回每消费组最新快照（>5 分钟告警判据的数据源，V1012 iot_consumer_stat）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅调用监控服务 + 编排响应；采样降级与指标读取归服务层。
 * 装配归 fuyun-app IotConfig @Import（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/iot/monitor")
public class MonitorController {

    /** 质量监控服务：积压快照采样与查询唯一业务出口 */
    private final IQualityService qualityService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param qualityService 质量监控服务，非空
     */
    public MonitorController(IQualityService qualityService) {
        this.qualityService = qualityService;
    }

    /**
     * 消费积压水位（GET /api/v1/iot/monitor/consumer-lag；每消费组最新快照，采样随查询惰性触发）。
     *
     * @return 每消费组最新快照清单（sampled_at 降序）；200；消费链未启用时返回既有快照或空清单
     */
    @GetMapping("/consumer-lag")
    public List<ConsumerStatVO> consumerLag() {
        return qualityService.refreshAndListConsumerLag();
    }
}
