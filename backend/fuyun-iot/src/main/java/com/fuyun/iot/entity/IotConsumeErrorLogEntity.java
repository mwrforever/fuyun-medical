package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.ConsumeErrorStage;
import com.fuyun.iot.enums.ConsumeErrorStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 消费错误日志实体（iot.iot_consume_error_log，V401 迁移）：AMQP 主链路失败消息统一落点。
 *
 * <p>毒丸隔离载体（14-iot FU-M14-01）：解析/校验/落库失败消息落本表留痕后确认抛弃，不阻塞消费
 * 队列；本表不经 fy.dlx（遥测不走 MQ 总线，总 Spec D2）。状态列使用
 * {@link ConsumeErrorStage}/{@link ConsumeErrorStatus} 枚举（MP @EnumValue 自动映射 VARCHAR 列）；
 * 有状态迁移生命周期（重放/放弃处置）故挂 updated_at 触发器；无 deleted（错误日志无逻辑删语义，
 * 处置以 status 终态表达，V401 文件头）。敏感红线：rawPayload 为脱敏截断后的载荷引用，
 * 禁原文敏感值全量入库。
 */
@Getter
@Setter
@TableName("iot.iot_consume_error_log")
public class IotConsumeErrorLogEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成，列名 error_id），禁止手动赋值 */
    @TableId(value = "error_id", type = IdType.ASSIGN_ID)
    private Long errorId;

    /** 来源订阅队列名（溯源消费链路） */
    private String queueName;

    /** 原文 SHA-256 十六进制摘要（64 位小写，DeadLetterListener 同口径） */
    private String rawDigest;

    /** 载荷引用（脱敏后 4000 字符截断留痕，禁原文敏感值全量入库），可空 */
    private String rawPayload;

    /** 失败阶段：PARSE 解析/VALIDATE 校验/PERSIST 落库 */
    private ConsumeErrorStage errorStage;

    /** 失败原因摘要（不带原文敏感值，500 字符内），可空 */
    private String errorMsg;

    /** 处置状态：PENDING 待处理/REPLAYED 已重放/ABANDONED 已放弃（P0 落库恒 PENDING） */
    private ConsumeErrorStatus status;

    /** 重放次数（每次重放累加，P0 恒 0） */
    private Integer replayCount;

    /** 处置操作人（未处置为空），可空 */
    private String handledBy;

    /** 处置时刻（未处置为空），可空 */
    private OffsetDateTime handledAt;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护（V1 公共函数），应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：系统操作为 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：同 createdBy 口径 */
    private String updatedBy;
}
