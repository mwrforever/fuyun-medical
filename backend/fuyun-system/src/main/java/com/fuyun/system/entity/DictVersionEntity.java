package com.fuyun.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.system.enums.DictVersionStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 字典版本实体（system.dict_version）：字典域的版本轴，承载 DRAFT→PUBLISHED→DEPRECATED 状态机。
 *
 * <p>版本化发布模型（M01 Spec §5）：同类型内版本号自增（uk(dict_type_id, version)），
 * 发布动作置 published_at/effective_at 并将同类型旧 PUBLISHED 行置 DEPRECATED（"同一 type
 * 同一时刻仅一个 PUBLISHED"，应用层保证）。状态列使用 {@link DictVersionStatus} 枚举
 * （MP @EnumValue 自动映射）；updated_at 由数据库触发器统一维护。
 */
@Getter
@Setter
@TableName("system.dict_version")
public class DictVersionEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属字典类型 ID */
    private Long dictTypeId;

    /** 版本号（同类型内自增，1 起） */
    private Integer version;

    /** 状态机：DRAFT 草稿/PUBLISHED 已发布/DEPRECATED 已废弃 */
    private DictVersionStatus status;

    /** 生效时刻（P0 发布即生效 = now()；fy.delay 定时生效随 P1），可空 */
    private OffsetDateTime effectiveAt;

    /** 发布时刻（DRAFT 阶段为 null），可空 */
    private OffsetDateTime publishedAt;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护（V1 公共函数），应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：种子/系统操作为 'system'（数据库默认值），业务操作经操作人上下文注入 */
    private String createdBy;

    /** 更新人：同 createdBy 口径 */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
