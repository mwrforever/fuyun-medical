package com.fuyun.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** 脱敏规则集中配置：rule_code 业务唯一（V103 uk），exempt_roles 逗号分隔角色编码 */
@Getter
@Setter
@TableName("patient.privacy_mask_rule")
public class PrivacyMaskRule {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成；V103 种子行固定 1-5 小整数） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 规则编码（业务唯一，如 MASK_NAME） */
    private String ruleCode;

    /** 目标字段词（PrivacyConstants.TARGET_*：name/idCardNo/mobile/address/birthDate） */
    private String targetField;

    /** 保留策略（PrivacyConstants.PATTERN_*：KEEP_FIRST/KEEP_6_4 等） */
    private String maskPattern;

    /** 豁免角色集合（逗号分隔角色编码；空串=无人豁免） */
    private String exemptRoles;

    /** 启用标记（false 时引擎跳过该规则，字段原值透传） */
    private Boolean enabled;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护，应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人 */
    private String createdBy;

    /** 更新人 */
    private String updatedBy;

    /** 逻辑删除标记（唯一索引仅在 deleted=0 生效） */
    @TableLogic
    private Integer deleted;
}
