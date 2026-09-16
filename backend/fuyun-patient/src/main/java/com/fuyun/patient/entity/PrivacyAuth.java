package com.fuyun.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 患者隐私授权实体（patient.privacy_auth）：个保法「单独同意」留痕载体（FU-M02-06 授权侧）。
 * 建档必须存在有效知情同意授权（FU-M02-01），由建档事务内落 INFORMED_CONSENT 行保证。
 */
@Getter
@Setter
@TableName("patient.privacy_auth")
public class PrivacyAuth {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 患者主索引 */
    private Long patientId;

    /** 授权类型（PrivacyAuthType：INFORMED_CONSENT/SENSITIVE_USE/GUARDIAN） */
    private String authType;

    /** 授权依据引用（纸质凭证编号/电子签名引用，签发经 M01 CA） */
    private String authBasis;

    /** 授权范围说明（可空） */
    private String scope;

    /** 签署时刻 */
    private OffsetDateTime signedAt;

    /** 失效时刻（空=长期有效；EXPIRED 由读侧按本列派生） */
    private OffsetDateTime validTo;

    /** 状态机：EFFECTIVE/EXPIRED/REVOKED（PrivacyAuthStatus） */
    private String status;

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
