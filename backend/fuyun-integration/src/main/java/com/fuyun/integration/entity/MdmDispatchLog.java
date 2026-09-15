package com.fuyun.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 主数据分发流水实体（integration.mdm_dispatch_log）：广播/全量重发的审计依据（M20 §4）。
 *
 * <p>只增台账：不设 @TableLogic（无逻辑删列）、无 updated_at 语义（不挂触发器）；dispatched_at 为
 * 业务时刻（信封 occurredAt，应用层写入），created_at 由数据库 DEFAULT now() 维护。
 */
@Getter
@Setter
@TableName("integration.mdm_dispatch_log")
public class MdmDispatchLog {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 主数据主题：dict/org/user/param/practice */
    private String topic;

    /** 分发版本号；可空（占位 schema 主题的载荷无 version 字段） */
    private Long version;

    /** 分发模式：BROADCAST 广播（MdmConstants.DISPATCH_MODE_BROADCAST） */
    private String dispatchMode;

    /** 分发时刻（信封 occurredAt，应用层写入） */
    private OffsetDateTime dispatchedAt;

    /** 分发目标模块清单（逗号分隔；空串=当时无订阅方） */
    private String targetModules;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;
}
