package com.fuyun.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 疑似重复待审实体（patient.possible_duplicate，M02 §4）：(patient_id_a, patient_id_b) 部分唯一
 * 兜底同对唯一；应用层保证 a&lt;b。
 *
 * <p>幂等口径：建档实时与批量扫描双渠道重复命中以唯一索引 DuplicateKeyException 静默幂等
 * （Spec §10「同一对患者不重复生成」）；deleted 逻辑删（唯一索引仅在 deleted=0 生效）。
 */
@Getter
@Setter
@TableName("patient.possible_duplicate")
public class PossibleDuplicate {

    /** 主键：雪花 ID（MP ASSIGN_ID 插入时生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 患者对 A（应用层保证 a &lt; b，防同对两行镜像） */
    private Long patientIdA;

    /** 患者对 B */
    private Long patientIdB;

    /** 匹配评分（0-100，一位小数；强标识矛盾固定 100） */
    private BigDecimal matchScore;

    /** 命中规则快照（文本形式；来源 EMPI 引擎结论） */
    private String matchedRules;

    /** 来源：REGISTER_SCAN 建档实时 / BATCH_SCAN 批量扫描（DuplicateSource） */
    private String source;

    /** 状态机：PENDING/MERGED/EXCLUDED（DuplicateStatus） */
    private String status;

    /** 审核人（已审核时非空） */
    private String reviewedBy;

    /** 审核时刻（已审核时非空） */
    private OffsetDateTime reviewedAt;

    /** 审核备注（EXCLUDED 时必填理由落痕） */
    private String reviewNote;

    /** 生成时刻：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时刻：数据库触发器统一维护，应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：默认 'system'，业务写路径由应用层注入操作人 */
    private String createdBy;

    /** 更新人：同上 */
    private String updatedBy;

    /** 逻辑删除标记（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
