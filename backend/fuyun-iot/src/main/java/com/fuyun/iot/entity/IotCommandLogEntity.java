package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.CommandDeliverMode;
import com.fuyun.iot.enums.CommandSafetyLevel;
import com.fuyun.iot.enums.CommandStatus;
import com.fuyun.iot.handler.JsonbTypeHandler;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 命令下发日志实体（iot.iot_command_log，V1009 迁移）：白名单+二次确认+五步下发的全要素留痕
 * 与状态机载体（FU-M14-09）。
 *
 * <p>状态机：ISSUED → DELIVERED → SUCCESS/FAILED；ISSUED 超时 → TIMEOUT（终态不可变更，
 * IotCommandLogMapper.casTerminal 以旧状态限定兜底）。updated_at 由数据库触发器统一维护
 * （V1 公共函数，宪法 A.4.2-9），应用层禁止写入；command_params 为 JSONB 列，经
 * {@link JsonbTypeHandler} 以 String 原文承载（原文透传）。
 */
@Getter
@Setter
@TableName(value = "iot.iot_command_log", autoResultMap = true)
public class IotCommandLogEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 应用层生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 命令业务号（CMD{yyyyMMdd}{%05d}，IotSeqGate 签发，凭证签发时预占） */
    private String commandNo;

    /** 目标设备号（关联 iot_device，应用层保证存在） */
    private String deviceId;

    /** 命令名称（物模型 commands[].name） */
    private String commandName;

    /** 命令参数快照（JSONB 原文，无参命令为空；JsonbTypeHandler 承载） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String commandParams;

    /** 命令安全等级：SAFETY 安全级/TREATMENT 治疗级（白名单标注快照） */
    private CommandSafetyLevel safetyLevel;

    /** 操作人（操作者上下文，无登录上下文回退 system） */
    private String operator;

    /** 确认引用：二次确认凭证标识 challengeId（GETDEL 一次性消费，回溯锚） */
    private String confirmRef;

    /** 下发通道：SYNC 在线同步/ASYNC 离线异步（在线预检自动选道） */
    private CommandDeliverMode deliverMode;

    /** 命令状态机：ISSUED/DELIVERED/SUCCESS/FAILED/TIMEOUT（终态不可变更） */
    private CommandStatus status;

    /** 下发时刻（落行时点，数据库 DEFAULT now() 维护） */
    private OffsetDateTime issuedAt;

    /** 结果时刻（回执/结果帧/超时判定时点） */
    private OffsetDateTime resultAt;

    /** 失败原因（SUCCESS 终态为空；回执/结果帧失败摘要或超时判定文案） */
    private String errorMsg;

    /** 全链路追踪号（下发请求 MDC 捕获，跨链路排查锚） */
    private String traceId;

    /** 创建时间：数据库 DEFAULT now() 维护 */
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
