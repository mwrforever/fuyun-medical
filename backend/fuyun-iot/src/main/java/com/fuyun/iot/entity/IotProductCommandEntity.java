package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.CommandSafetyLevel;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 产品命令安全等级实体（iot.iot_product_command，V1007 迁移）：命令白名单数据源
 * （FU-M14-09 下发闸门按 productId+commandName 查 allowed/safetyLevel）。行随管理台
 * PUT 全量替换重建（逻辑删旧行 + 插入新行，部分唯一索引只约束未删行）。
 */
@Getter
@Setter
@TableName("iot.iot_product_command")
public class IotProductCommandEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 应用层生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** IoTDA 产品标识（关联 iot_product 自然键） */
    private String productId;

    /** 命令名称（物模型 commands[].name） */
    private String commandName;

    /** 所属服务 ID（物模型 service 维度），可空 */
    private String serviceId;

    /** 命令安全等级：SAFETY 安全级/TREATMENT 治疗级 */
    private CommandSafetyLevel safetyLevel;

    /** 是否放行下发（落行默认按级别：SAFETY=true/TREATMENT=false） */
    private Boolean allowed;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护（V1 公共函数），应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：种子/系统操作为 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：同 createdBy 口径 */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
