package com.fuyun.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 主数据分发订阅台账实体（integration.mdm_subscription）：M20 分发关系与对账依据（M20 §4）。
 *
 * <p>写入语义：订阅方登记（register）落行，recon_status 初值 PENDING；注销置逻辑删（deleted=1）。
 * last_version/last_sync_at/last_recon_at/recon_status 的写路径属对账任务（依赖 M01 版本化回源
 * 接口，未在本 PR 交付）——本实体保留字段以承载 Spec 字段全集（V502 随表落盘），零应用层写路径。
 *
 * <p>updated_at 由数据库触发器维护（V1 公共函数）；deleted 为逻辑删标记（唯一索引仅在 deleted=0 上生效）。
 */
@Getter
@Setter
@TableName("integration.mdm_subscription")
public class MdmSubscription {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 主数据主题：dict/org/user/param/practice（MdmConstants.TOPICS 校验） */
    private String topic;

    /** 订阅方模块域标识（如 system/patient） */
    private String subscriberModule;

    /** 同步方式：EVENT_SUBSCRIBE 事件订阅 / API_PULL 接口拉取 */
    private String syncMode;

    /** 订阅方已同步到的版本号（可空；对账任务写路径） */
    private Long lastVersion;

    /** 最近一次同步时刻（可空；对账任务写路径） */
    private OffsetDateTime lastSyncAt;

    /** 最近一次对账时刻（可空；对账任务写路径） */
    private OffsetDateTime lastReconAt;

    /** 对账状态：PENDING 待对账（登记初值） */
    private String reconStatus;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护，应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：治理台账默认 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：治理台账默认 'system'（数据库默认值） */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
