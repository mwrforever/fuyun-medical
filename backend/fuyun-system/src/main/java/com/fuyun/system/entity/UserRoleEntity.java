package com.fuyun.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户-角色关联实体（system.sys_user_role）：RBAC0 用户轴与角色轴的绑定关系。
 *
 * <p>P0 只有 INSERT/DELETE（授权与回收），无 UPDATE 生命周期故 DDL 不挂 updated_at 触发器；
 * (user_id, role_id) 联合唯一（deleted=0 范围内）。
 */
@Getter
@Setter
@TableName("system.sys_user_role")
public class UserRoleEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 角色 ID */
    private Long roleId;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：无 UPDATE 生命周期，值恒等于插入时刻（数据库 DEFAULT now()） */
    private OffsetDateTime updatedAt;

    /** 创建人：种子/系统操作为 'system'（数据库默认值），业务操作经操作人上下文注入 */
    private String createdBy;

    /** 更新人：同 createdBy 口径 */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
