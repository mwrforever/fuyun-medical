package com.fuyun.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.system.enums.OrgAttr;
import com.fuyun.system.enums.OrgStatus;
import com.fuyun.system.enums.OrgType;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 机构实体（system.sys_org）：院区/科室/病区/班组四级组织树节点（M01 Spec §4）。
 *
 * <p>P0 仅邻接表 parent_id 表达树形（组织树查询非 P0 验收项），树形闭包表随 P1 组织管理补；
 * 根节点以 parentId 为 null 表达。状态列使用 {@link OrgType}/{@link OrgAttr}/{@link OrgStatus} 枚举
 * （MP @EnumValue 自动映射）；updated_at 由数据库触发器统一维护。
 */
@Getter
@Setter
@TableName("system.sys_org")
public class OrgEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构编码，业务唯一（deleted=0 范围内） */
    private String orgCode;

    /** 机构名称 */
    private String orgName;

    /** 机构类型：CAMPUS 院区/DEPT 科室/WARD 病区/TEAM 班组 */
    private OrgType orgType;

    /** 机构属性：CLINICAL 临床/MEDTECH 医技/ADMIN 职能 */
    private OrgAttr orgAttr;

    /** 父机构 ID；null=根节点 */
    private Long parentId;

    /** 同级排序号，小者在前 */
    private Integer sort;

    /** 状态：ACTIVE 启用/DISABLED 停用 */
    private OrgStatus status;

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
