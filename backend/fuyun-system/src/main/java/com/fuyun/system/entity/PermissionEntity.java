package com.fuyun.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.system.enums.PermissionType;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 权限点实体（system.sys_permission）：接口权限点以 API 路径为编码（M01 FU-M01-03），
 * 与菜单权限点收敛单表（Spec role_menu/role_api 收敛形态）。
 *
 * <p>P0 权限点由 V303 种子登记（受保护端点全集）；API 权限强制（403 鉴权拦截）随 P1，
 * P0 仅认证 401。类型列使用 {@link PermissionType} 枚举（MP @EnumValue 自动映射）；
 * updated_at 由数据库触发器统一维护。
 */
@Getter
@Setter
@TableName("system.sys_permission")
public class PermissionEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 权限点编码 = API 路径（如 /api/v1/system/dicts/{type}），业务唯一（deleted=0 范围内） */
    private String permCode;

    /** 权限点名称 */
    private String permName;

    /** 类型：MENU 菜单/API 接口 */
    private PermissionType permType;

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
