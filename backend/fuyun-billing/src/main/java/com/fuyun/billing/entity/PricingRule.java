package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.enums.TriggerType;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 计价规则实体（billing.pricing_rule，FU-M13-02 规则配置面）：trigger_type 七值事件驱动分流；
 * item_scope 存项目集合 JSON 文本（解析失败即配置错误抛异常拒算，禁静默）。
 * 线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("billing.pricing_rule")
public class PricingRule {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 规则编码（业务唯一） */
    private String ruleCode;

    /** 规则名称 */
    private String ruleName;

    /** 触发型七值（ORDER_CONFIRMED/PRESCRIPTION_EFFECTIVE/EXECUTED/REGISTERED/SCANNED/DURATION/MANUAL） */
    private TriggerType triggerType;

    /** 项目集合 JSON 文本（{"itemClasses":[...],"itemIds":[...]} 二选一或并集） */
    private String itemScope;

    /** 规则状态 ACTIVE/INACTIVE */
    private ItemStatus status;

    /** 备注（可空） */
    private String remark;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护 */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入 */
    private String createdBy;

    /** 审计列 */
    private String updatedBy;

    /** 逻辑删标记（@TableLogic 全局配置） */
    @TableLogic
    private Short deleted;
}
