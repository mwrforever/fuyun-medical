package com.fuyun.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 死信台账实体（integration.dead_letter）：全系统死信统一落库与告警的载体（M20 §5）。
 *
 * <p>写入语义：死信监听器对 q.integration.dead-letter 每帧插入 PENDING 行（重放/关闭状态机
 * 随 P1 死信管理界面交付）；信封不合规帧拒收留痕（event_id/event_type 两列置空、fail_reason
 * 标注）。只增台账：不设 @TableLogic（无逻辑删列）、无 updated_at 语义（不挂触发器），
 * first_dead_at/created_at 由数据库 DEFAULT now() 维护；无唯一约束（同一 eventId 可因不同
 * 消费者多次死信重复落行，P1 死信界面完整化时收敛）。
 */
@Getter
@Setter
@TableName("integration.dead_letter")
public class DeadLetter {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 来源队列（x-death[].queue），消费重试耗尽/不合规帧死信的出队溯源 */
    private String sourceQueue;

    /** 原始路由键（=事件类型），死信转发保留原始路由键（消费队列不设死信路由键） */
    private String routingKey;

    /** 信封 eventType；信封不合规帧为空 */
    private String eventType;

    /** 信封 eventId（字符串承载，与 received_event 的 UUID 列区分）；信封不合规帧为空 */
    private String eventId;

    /** 原始消息体全文（重放依赖原文；M20 "payload 引用与摘要"落地形态，BRIEF-PR2-01 §8-6） */
    private String payloadBody;

    /** 原文 SHA-256 摘要（64 位十六进制，列表页快速比对） */
    private String payloadDigest;

    /** 死信原因（消费重试耗尽 reason 透传 / 信封不合规标注），落库必填 */
    private String failReason;

    /** 首次死信时间：数据库 DEFAULT now() 维护，应用层不写 */
    private OffsetDateTime firstDeadAt;

    /** 处理状态：PENDING 待处理（P0 唯一写入值）/ REPLAYED 已重放 / CLOSED 已关闭（P1 状态机，字符串常量） */
    private String status;

    /** 重放计数（P1 死信管理界面启用，P0 恒为数据库默认 0） */
    private Integer replayCount;

    /** 处理人（P1 启用） */
    private String handler;

    /** 处理备注（P1 启用） */
    private String handleNote;

    /** 处理时间（P1 启用） */
    private OffsetDateTime handledAt;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;
}
