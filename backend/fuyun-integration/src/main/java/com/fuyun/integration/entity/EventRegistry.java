package com.fuyun.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 事件契约台账实体（integration.event_registry）：全系统事件类型的登记与订阅清单载体（M20 §3）。
 *
 * <p>生命周期：发布方（或种子迁移）登记契约行 → 订阅方经声明构件追加订阅模块 → 契约冻结后
 * 禁止静默改写（重复登记幂等跳过）。updated_at 由数据库触发器统一维护（V1 公共函数，
 * 宪法 A.4.2-9），应用层不写时间戳列；deleted 为逻辑删标记（唯一索引仅在 deleted=0 上生效）。
 */
@Getter
@Setter
@TableName("integration.event_registry")
public class EventRegistry {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 事件类型 {@code <模块>.<实体>.<动作>}，全表业务唯一（deleted=0 范围内） */
    private String eventType;

    /** 生产模块域标识（如 system），登记时确定 */
    private String producerModule;

    /** 载荷结构说明（冻结契约摘要），供订阅方对齐契约 */
    private String payloadDesc;

    /** 订阅模块清单（逗号分隔）；broadcast=零订阅广播标记（R6-13），空串=待订阅 */
    private String subscriberModules;

    /** 契约状态：ACTIVE 生效 / DEPRECATED 废止（字符串常量，D-6 未决不落枚举） */
    private String status;

    /** 业务登记时间：数据库 DEFAULT now() 维护，应用层不写 */
    private OffsetDateTime registeredAt;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护（V1 公共函数），应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：治理台账默认 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：治理台账默认 'system'（数据库默认值） */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
