package com.fuyun.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 字典类型实体（system.dict_type）：字典域的类型轴（如 gender/icd10），承载编码唯一性与国标标记。
 *
 * <p>生命周期：创建 → 版本化管理（dict_version 按类型递增版本号）；国标字典（national_standard=true）
 * 编码不可修改（FU-M01-06，修改拦截随 P1 编辑端点接入）。updated_at 由数据库触发器统一维护。
 */
@Getter
@Setter
@TableName("system.dict_type")
public class DictTypeEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 字典类型编码，业务唯一（deleted=0 范围内，如 gender） */
    private String typeCode;

    /** 字典类型名称 */
    private String typeName;

    /** 国标字典标记：true=编码不可修改（FU-M01-06） */
    private Boolean nationalStandard;

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
