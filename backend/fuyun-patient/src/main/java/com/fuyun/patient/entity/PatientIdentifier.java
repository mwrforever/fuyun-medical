package com.fuyun.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 患者标识注册表实体（patient.patient_identifier）：一切介质与证件挂接主索引的载体（M02 §3.2）。
 *
 * <p>唯一性：(identifier_type, value_hash) 部分唯一索引兜底并发重复登记（PAT-1002）；
 * 标识值仅存密文与 HMAC 摘要，明文禁落库禁日志（M02 红线 3）。
 */
@Getter
@Setter
@TableName("patient.patient_identifier")
public class PatientIdentifier {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 挂接的主索引（合并时整批改挂主档，原挂接入 merge_record 快照） */
    private Long patientId;

    /** 标识类型（IdentifierType 词表） */
    private String identifierType;

    /** 标识值密文（AES-GCM，Base64） */
    private String identifierValueCipher;

    /** 标识值 HMAC 盲索引（解析服务等值查主键路径） */
    private String valueHash;

    /** 卡面号（卡类介质：VISIT_CARD/HEALTH_CARD；非卡介质为空） */
    private String cardNo;

    /** 状态机：ACTIVE/LOST/REPLACED/DISABLED（IdentifierStatus） */
    private String status;

    /** 主标识标记（同档至多一条 ACTIVE 主标识，应用层保证） */
    private Boolean isPrimary;

    /** 绑定时刻（数据库 DEFAULT now()） */
    private OffsetDateTime boundAt;

    /** 解绑/失效时刻（LOST/DISABLED 落） */
    private OffsetDateTime unboundAt;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护 */
    private OffsetDateTime updatedAt;

    /** 创建人 */
    private String createdBy;

    /** 更新人 */
    private String updatedBy;

    /** 逻辑删除标记 */
    @TableLogic
    private Integer deleted;
}
