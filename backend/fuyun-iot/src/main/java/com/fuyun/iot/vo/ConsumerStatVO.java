package com.fuyun.iot.vo;

import com.fuyun.iot.entity.IotConsumerStatEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 消费积压快照视图（GET /api/v1/iot/monitor/consumer-lag 出参，FU-M14-01 积压观测面）。
 *
 * @param consumerGroup     消费组/队列标识，非空
 * @param sampledAt         采样时刻，非空
 * @param oldestMsgAgeSecs  最旧未消费消息年龄秒；IoTDA 侧真实积压本地不可得为 null（随联调补全）
 * @param consumeRate       消费速率（条/秒）；本地不可得为 null
 * @param arriveRate        到达速率（条/秒）；本地不可得为 null
 * @param backlogEstimate   积压水位估计（本地口径=攒批队列填充率 0~1）；指标未注册（消费链关闭）为 null
 */
public record ConsumerStatVO(
        String consumerGroup,
        OffsetDateTime sampledAt,
        Long oldestMsgAgeSecs,
        BigDecimal consumeRate,
        BigDecimal arriveRate,
        BigDecimal backlogEstimate) {

    /**
     * 快照实体 → 出网视图（唯一转换出口，字段一一对应浅拷贝）。
     *
     * @param entity 快照实体，非空
     * @return 快照视图，非空
     */
    public static ConsumerStatVO from(IotConsumerStatEntity entity) {
        return new ConsumerStatVO(
                entity.getConsumerGroup(),
                entity.getSampledAt(),
                entity.getOldestMsgAgeSecs(),
                entity.getConsumeRate(),
                entity.getArriveRate(),
                entity.getBacklogEstimate());
    }
}
