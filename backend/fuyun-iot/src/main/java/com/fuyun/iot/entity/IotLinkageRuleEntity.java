package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.LinkageActionType;
import com.fuyun.iot.enums.LinkageTriggerSource;
import com.fuyun.iot.handler.JsonbTypeHandler;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 联动规则实体（iot.linkage_rule，V1010 迁移）：触发-条件-动作三段式配置载体（FU-M14-10）。
 *
 * <p>雪花代理主键（@TableId(ASSIGN_ID)，宪法 A.4.3-16）；trigger_condition/action_config 为
 * JSONB 列，经 {@link JsonbTypeHandler} 以 String 原文承载（原文透传）；updated_at 由数据库
 * 触发器统一维护（V1 公共函数，宪法 A.4.2-9），应用层不写时间戳列。
 */
@Getter
@Setter
@TableName(value = "iot.linkage_rule", autoResultMap = true)
public class IotLinkageRuleEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 规则名称（管理台展示名） */
    private String ruleName;

    /** 触发来源：ALARM_TRIGGERED/TELEMETRY_ANOMALY/DEVICE_STATUS */
    private LinkageTriggerSource triggerSource;

    /** 触发条件（JSONB 原文：alarm_type/metric_code/device_type 键值等值匹配；JsonbTypeHandler 承载） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String triggerCondition;

    /** 动作类型：NOTIFY/M01_NOTIFY/CALL_TRANSFER/NURSING_TASK/WARD_BROADCAST */
    private LinkageActionType actionType;

    /** 动作配置快照（JSONB 原文，可空；JsonbTypeHandler 承载） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String actionConfig;

    /** 目标病区 ID（可空：空=跟随触发源病区路由） */
    private Long targetWardId;

    /** 是否启用（禁用规则不参与触发匹配） */
    private Boolean enabled;

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
