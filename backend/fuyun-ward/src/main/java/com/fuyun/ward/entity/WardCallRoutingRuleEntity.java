package com.fuyun.ward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.ward.enums.CallType;
import com.fuyun.ward.handler.JsonbTypeHandler;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 呼叫路由规则实体（ward.ward_call_routing_rule，V1100 迁移）：病区+类型+时段三维定位转接目标链，
 * 转接触发时解析（无规则 WD-1003）。
 *
 * <p>雪花代理主键（@TableId(ASSIGN_ID)）；target_chain 为 JSONB 列（JsonbTypeHandler 承载原文
 * 透传）；updated_at 由数据库触发器统一维护。
 */
@Getter
@Setter
@TableName(value = "ward.ward_call_routing_rule", autoResultMap = true)
public class WardCallRoutingRuleEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 病区 ID（规则三要素之一） */
    private Long wardId;

    /** 呼叫类型（规则三要素之二） */
    private CallType callType;

    /** 生效时段（规则三要素之三）：HHmm-HHmm 文本（起含终不含） */
    private String timeRange;

    /** 目标链（JSONB 字符串数组原文，按序转接目标） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String targetChain;

    /** 任务转换开关（EMERGENCY 类病区可开：转接触发 M05 任务创建——PR-3 闭合） */
    private Boolean taskConvertFlag;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护，应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：种子/系统操作为 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：同 createdBy 口径 */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
