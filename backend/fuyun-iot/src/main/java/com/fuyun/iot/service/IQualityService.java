package com.fuyun.iot.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.QualityStatQueryRequest;
import com.fuyun.iot.vo.ConsumerStatVO;
import com.fuyun.iot.vo.DataQualityStatVO;
import java.util.List;

/**
 * 数据质量监控服务（FU-M14-11，P2 PR-2 Task 10）：质量日统计（惰性重算落库）、消费积压快照
 * 采样落表与遥测断流异常判定发布。统计/采样/判定全部惰性触发（随查询调起），批量定时调度随
 * P3 归 Task 18 Spec（本 PR 只落服务与端点）。
 */
public interface IQualityService {

    /**
     * 质量日统计查询（统计落库随查询惰性触发）：显式指定 deviceId 时先重算该设备当日统计行
     * （UPSERT 覆盖）并顺带执行断流判定，再分页返回统计行。
     *
     * @param request 查询请求，非空（statDate 缺省当日，deviceId 缺省不过滤）
     * @return 统计分页出参（含利用率折算），非空
     * @throws com.fuyun.common.exception.BizException IOT-1006（404；deviceId 显式指定但设备不存在）
     */
    PageResult<DataQualityStatVO> qualityStats(QualityStatQueryRequest request);

    /**
     * 设备利用率查询（FU-M15-05 数据源）：与质量统计同表同惰性重算语义，出参侧重利用率折算。
     *
     * @param request 查询请求，非空
     * @return 统计分页出参（usageRate 为核心字段），非空
     * @throws com.fuyun.common.exception.BizException IOT-1006（404；deviceId 显式指定但设备不存在）
     */
    PageResult<DataQualityStatVO> deviceUsage(QualityStatQueryRequest request);

    /**
     * 消费积压快照采样 + 查询（AMQP 指标快照落表随查询惰性触发）：读本地指标面（攒批队列填充率）
     * 落一行快照后返回每消费组最新快照；消费链未启用（指标未注册）跳过采样仅返回既有快照。
     *
     * @return 每消费组最新快照清单（sampled_at 降序），非空；无采样行为空清单
     */
    List<ConsumerStatVO> refreshAndListConsumerLag();

    /**
     * 遥测断流判定（惰性判定入口，P3 定时调度复用）：在线设备中，登记标称频率的指标超过
     * N 倍标称周期无有效采集 → 发布 iot.telemetry.anomaly（STREAM_GAP，Redis 去重门闸
     * 1 小时窗口内同设备同指标仅发一次）。
     *
     * @return 本次发布的事件条数（去重门闸拦截不计入）
     */
    int detectTelemetryAnomalies();
}
