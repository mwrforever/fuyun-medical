package com.fuyun.iot.vo;

import com.fuyun.iot.entity.IotConsumeErrorLogEntity;
import com.fuyun.iot.enums.ConsumeErrorStage;
import com.fuyun.iot.enums.ConsumeErrorStatus;
import java.time.OffsetDateTime;

/**
 * 消费错误日志视图（消费错误管理端点出网载体，V401 P0 遗留义务补齐）：实体禁直出（宪法 B.1
 * 出网边界）；rawPayload 为脱敏截断后的载荷引用（敏感红线：管理端展示亦不出原文敏感值）。
 *
 * @param errorId    错误行雪花 ID，非空
 * @param queueName  来源订阅队列名，非空
 * @param rawDigest  原文 SHA-256 摘要（64 位小写），非空
 * @param rawPayload 脱敏截断载荷引用，可空
 * @param errorStage 失败阶段：PARSE/VALIDATE/PERSIST，非空
 * @param errorMsg   失败原因摘要，可空（ABANDONED 后追加强弃原因）
 * @param status     处置状态：PENDING/REPLAYED/ABANDONED，非空
 * @param replayCount 重放次数（每次重放累加），非空
 * @param handledBy  处置操作人，可空
 * @param handledAt  处置时刻，可空
 * @param createdAt  落库时刻，非空
 */
public record ConsumeErrorVO(
        Long errorId,
        String queueName,
        String rawDigest,
        String rawPayload,
        ConsumeErrorStage errorStage,
        String errorMsg,
        ConsumeErrorStatus status,
        Integer replayCount,
        String handledBy,
        OffsetDateTime handledAt,
        OffsetDateTime createdAt) {

    /**
     * 实体 → 出网视图（唯一转换出口，字段一一对应浅拷贝）。
     *
     * @param entity 错误日志实体，非空
     * @return 错误日志视图，非空
     */
    public static ConsumeErrorVO from(IotConsumeErrorLogEntity entity) {
        return new ConsumeErrorVO(
                entity.getErrorId(),
                entity.getQueueName(),
                entity.getRawDigest(),
                entity.getRawPayload(),
                entity.getErrorStage(),
                entity.getErrorMsg(),
                entity.getStatus(),
                entity.getReplayCount(),
                entity.getHandledBy(),
                entity.getHandledAt(),
                entity.getCreatedAt());
    }
}
