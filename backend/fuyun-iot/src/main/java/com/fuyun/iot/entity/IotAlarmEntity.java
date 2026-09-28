package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 告警行实体（iot.iot_alarm，V1008 迁移）：告警引擎产出与生命周期（活跃/确认/关闭）载体，
 * 绑定快照五元组冗余（FU-M14-08）。
 *
 * <p>雪花代理主键（@TableId(ASSIGN_ID)）；对外标识为 alarm_no 业务号（IotSeqGate.nextAlarmNo，
 * AL{yyyyMMdd}{%05d}）；同设备同规则至多一条活跃行由部分唯一索引 uk_iot_alarm_active 兜底
 * （抑制①同源聚合的 DB 防线）；updated_at 由数据库触发器统一维护，应用层不写时间戳列。
 */
@Getter
@Setter
@TableName("iot.iot_alarm")
public class IotAlarmEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 告警业务号（AL{yyyyMMdd}{%05d}，IotSeqGate 签发，全局唯一） */
    private String alarmNo;

    /** 命中规则 ID（关联 iot_alarm_rule） */
    private Long ruleId;

    /** 告警设备号 */
    private String deviceId;

    /** 患者主索引（触发时绑定快照冗余，无绑定/公共设备为空），可空 */
    private Long patientId;

    /** 住院就诊号（CF-3 I 型 14 位，触发时绑定快照冗余），可空 */
    private String visitId;

    /** 病区 ID（绑定快照或设备档案，WS 推送路由键） */
    private Long wardId;

    /** 告警级别：INFO/WARNING/CRITICAL（触发时规则级别快照） */
    private AlarmLevel alarmLevel;

    /** 指标编码（离线告警为固定值 DEVICE_OFFLINE） */
    private String metricCode;

    /** 触发值原文（保留原始形态；离线告警为最后在线时刻文本） */
    private String triggerValue;

    /** 告警状态：ACTIVE/ACKNOWLEDGED/CLOSED */
    private AlarmStatus status;

    /** 累计触发次数（抑制①同源聚合：活跃期内重复触发仅本列+1 不新发） */
    private Integer triggerCount;

    /** 最近触发时刻（同源聚合时刷新） */
    private OffsetDateTime lastTriggeredAt;

    /** 已升级次数（抑制⑤防重发锚：CAS 限定旧值，仅首个升级方发布事件） */
    private Integer escalationCount;

    /** 最近升级时刻（升级时限计算锚，空则回退 created_at），可空 */
    private OffsetDateTime lastEscalatedAt;

    /** 确认人（ACTIVE→ACKNOWLEDGED 写入），可空 */
    private String acknowledgedBy;

    /** 确认时刻，可空 */
    private OffsetDateTime acknowledgedAt;

    /** 关闭人（人工关闭为操作者；自动恢复预留 system），可空 */
    private String closedBy;

    /** 关闭时刻，可空 */
    private OffsetDateTime closedAt;

    /** 关闭原因（关闭操作必填），可空 */
    private String closeReason;

    /** 全链路追踪号（触发点 MDC 捕获，跨链路排查锚），可空 */
    private String traceId;

    /** 创建时间：数据库 DEFAULT now() 维护（读路径升级时限计算锚） */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护（V1 公共函数），应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：种子/系统操作为 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：同 createdBy 口径 */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
