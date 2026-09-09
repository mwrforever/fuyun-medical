package com.fuyun.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.system.enums.UserStatus;
import com.fuyun.system.enums.UserType;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户账号实体（system.sys_user）：登录认证主体，承载登录名、bcrypt 口令哈希与登录状态机。
 *
 * <p>生命周期：V303 种子或用户管理创建 → 登录成功/失败驱动 fail_count/locked_until/last_login_at 变更 →
 * 停用/逻辑删。状态列使用 {@link UserStatus}/{@link UserType} 枚举（MP @EnumValue 自动映射 VARCHAR 列）；
 * updated_at 由数据库触发器统一维护（V1 公共函数，宪法 A.4.2-9），应用层不写时间戳列。
 */
@Getter
@Setter
@TableName("system.sys_user")
public class UserEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录名，业务唯一（deleted=0 范围内，部分唯一索引兜底） */
    private String loginName;

    /** bcrypt 口令哈希（$2a$ 60 字符），禁明文；敏感字段禁入日志 */
    private String passwordHash;

    /** 账号类型：STAFF 员工/SYSTEM 系统/API 接口 */
    private UserType userType;

    /** 账号状态：ACTIVE 正常/LOCKED 锁定/DISABLED 停用 */
    private UserStatus status;

    /** 连续登录失败计数（成功登录清零；达锁定阈值置 locked_until，阈值见 SecurityConstants） */
    private Integer failCount;

    /** 锁定截止时刻；null=未锁定，到期自动恢复（P0 无手动解锁端点） */
    private OffsetDateTime lockedUntil;

    /** 口令更新时刻（P1 密码策略启用，先落列避 ALTER），可空 */
    private OffsetDateTime passwordUpdatedAt;

    /** 最近成功登录时刻，可空 */
    private OffsetDateTime lastLoginAt;

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
