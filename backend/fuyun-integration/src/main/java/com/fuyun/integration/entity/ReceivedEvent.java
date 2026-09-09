package com.fuyun.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * 消费幂等台账实体（integration.received_event）：消费侧两层幂等的最终兜底载体（M20 §4）。
 *
 * <p>写入语义：消费方业务成功后经幂等构件插入 PROCESSED 行；(event_id, consumer_module)
 * 唯一索引（V3）为并发重复投递的最终拦截（冲突吞为已处理）。只增台账：不设 @TableLogic
 * （无逻辑删列）、无 updated_at 语义（不挂触发器），received_at/created_at 由数据库
 * DEFAULT now() 维护，应用层不写；保留 180 天后归档清理（M20 §9，归档策略 P1+ 完整化）。
 */
@Getter
@Setter
@TableName("integration.received_event")
public class ReceivedEvent {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 事件信封 eventId，全局唯一；幂等键第一要素（与 consumer_module 联合唯一） */
    private UUID eventId;

    /** 事件类型 {@code <模块>.<实体>.<动作>} */
    private String eventType;

    /** 生产模块域标识（如 system） */
    private String producer;

    /** 事件发生时刻（信封字段，UTC 语义） */
    private OffsetDateTime occurredAt;

    /** 消费者模块域标识；幂等键第二要素（同事件可被多模块各自消费） */
    private String consumerModule;

    /** 消费状态：PROCESSED 已消费（P0 唯一写入值）；FAILED 预留 P1 消费失败登记（字符串常量，不落枚举） */
    private String status;

    /** 消费失败原因（P1 消费失败登记启用，P0 不写） */
    private String failReason;

    /** 消费重试计数（P1 启用，P0 恒为数据库默认 0） */
    private Integer retryCount;

    /** 接收时间：数据库 DEFAULT now() 维护，应用层不写 */
    private OffsetDateTime receivedAt;

    /** 处理完成时间：成功登记时由应用层写入（业务时间戳，非审计触发器列） */
    private OffsetDateTime processedAt;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;
}
