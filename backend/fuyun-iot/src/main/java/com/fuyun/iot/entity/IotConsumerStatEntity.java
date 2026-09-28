package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 消费积压监控快照实体（iot.iot_consumer_stat，V1012 迁移）：FU-M14-01 积压观测面的时序快照
 * 落表载体（随监控查询惰性采样，时序只增）。
 *
 * <p>只增口径（V402 先例）：采样恒 INSERT 不 UPDATE，无审计列、无 updated_at 触发器、无 deleted；
 * 同一消费组同一采样时刻唯一（uk_iot_consumer_stat_group_sampled 防重复采样落行）。四个指标列
 * 全可空：真实积压水位/消费速率/到达速率本地不可得（IoTDA 侧数据，随联调补全），本地仅攒批
 * 队列填充率口径可测（backlog_estimate）。
 */
@Getter
@Setter
@TableName("iot.iot_consumer_stat")
public class IotConsumerStatEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成，禁止手动赋值） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 消费组/队列标识（本地攒批消费链固定组 iot-amqp） */
    private String consumerGroup;

    /** 采样时刻（与消费组联合唯一；库端 DEFAULT now() 兜底，应用层显式赋值保证同批一致） */
    private OffsetDateTime sampledAt;

    /** 最旧未消费消息年龄秒（IoTDA 侧真实积压，本地不可得为 NULL） */
    private Long oldestMsgAgeSecs;

    /** 消费速率（条/秒，本地不可得为 NULL） */
    private BigDecimal consumeRate;

    /** 到达速率（条/秒，本地不可得为 NULL） */
    private BigDecimal arriveRate;

    /** 积压水位估计（本地口径=攒批队列填充率 0~1） */
    private BigDecimal backlogEstimate;
}
