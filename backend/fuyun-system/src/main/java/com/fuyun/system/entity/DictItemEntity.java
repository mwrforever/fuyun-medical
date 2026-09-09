package com.fuyun.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 字典条目实体（system.dict_item）：字典域的条目轴，承载编码/名称/层级/排序。
 *
 * <p>条目仅可在草稿（DRAFT）版本内维护，已发布版本禁改（版本不可变性，M01 Spec §5）。
 * ext_attrs JSONB 列 P0 不暴露（插入走列默认值 '{}'，避免引入 JSONB TypeHandler，
 * 扩展属性读写随 P1 handler/ 交付）；updated_at 由数据库触发器统一维护。
 */
@Getter
@Setter
@TableName("system.dict_item")
public class DictItemEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属字典版本 ID */
    private Long dictVersionId;

    /** 条目编码（同版本内唯一，uk(dict_version_id, item_code)） */
    private String itemCode;

    /** 条目名称 */
    private String itemName;

    /** 父条目编码（null=顶层，层级字典树形表达），可空 */
    private String parentCode;

    /** 扩展属性（JSONB）：P0 不暴露不读写（列默认值承载），P1 随 TypeHandler 接入 */
    private String extAttrs;

    /** 排序号，小者在前（读接口按 sort 升序返回） */
    private Integer sort;

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
