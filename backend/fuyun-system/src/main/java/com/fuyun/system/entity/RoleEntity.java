package com.fuyun.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.system.enums.DataScopeType;
import com.fuyun.system.enums.RoleStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色实体（system.sys_role）：RBAC0 的角色轴，承载角色编码/数据范围/启停状态。
 *
 * <p>内置角色（ADMIN）由 V303 种子落库；用户经 sys_user_role 关联角色，角色经 sys_role_permission
 * 关联权限点。状态列使用 {@link RoleStatus}/{@link DataScopeType} 枚举（MP @EnumValue 自动映射）；
 * updated_at 由数据库触发器统一维护。
 */
@Getter
@Setter
@TableName("system.sys_role")
public class RoleEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 角色编码，业务唯一（deleted=0 范围内，如 ADMIN） */
    private String roleCode;

    /** 角色名称 */
    private String roleName;

    /** 数据范围类型：ALL/HOSP/DEPT/WARD/SELF（P0 仅落列与种子取值，注入拦截随 P1） */
    private DataScopeType dataScopeType;

    /** 状态：ACTIVE 启用/DISABLED 停用 */
    private RoleStatus status;

    /** 备注，可空 */
    private String remark;

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
