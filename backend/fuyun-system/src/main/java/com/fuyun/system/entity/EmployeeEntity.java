package com.fuyun.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.system.enums.EmployeeStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 员工档案实体（system.sys_employee）：与登录账号一对一（user_id 唯一），承载工号/姓名/职务/主归属机构。
 *
 * <p>P0 单主归属 primary_org_id 表达机构归属（多机构 junction 随 P1 补）；登录成功后经 user_id 反查
 * 组装会话的员工身份（eid）。状态列使用 {@link EmployeeStatus} 枚举（MP @EnumValue 自动映射）；
 * updated_at 由数据库触发器统一维护。
 */
@Getter
@Setter
@TableName("system.sys_employee")
public class EmployeeEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联登录账号 ID，一对一（deleted=0 范围内唯一索引兜底） */
    private Long userId;

    /** 工号，业务唯一（deleted=0 范围内） */
    private String empNo;

    /** 员工姓名 */
    private String empName;

    /** 职务职称，可空 */
    private String title;

    /** 主归属机构 ID；P0 种子可为 null（无机构行） */
    private Long primaryOrgId;

    /** 在职状态：ACTIVE 在职/DISABLED 停用 */
    private EmployeeStatus status;

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
