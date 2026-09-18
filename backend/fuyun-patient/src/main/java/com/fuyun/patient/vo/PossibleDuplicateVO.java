package com.fuyun.patient.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 疑似重复出参（工作台列表行；双档对照明细经患者详情端点分别拉取）。
 */
@Getter
@Setter
public class PossibleDuplicateVO {

    /** 待审行 id */
    private Long id;

    /** 患者对 A（a&lt;b 规范化） */
    private Long patientIdA;

    /** 患者对 B */
    private Long patientIdB;

    /** 匹配评分（0-100） */
    private BigDecimal matchScore;

    /** 命中规则名清单（读侧由库值归一还原，历史 toString 形态兼容解析） */
    private List<String> matchedRules;

    /** 来源 REGISTER_SCAN/BATCH_SCAN */
    private String source;

    /** 状态 PENDING/MERGED/EXCLUDED */
    private String status;

    /** 审核人（已审核时非空） */
    private String reviewedBy;

    /** 审核时刻 */
    private OffsetDateTime reviewedAt;

    /** 审核备注（EXCLUDED 理由） */
    private String reviewNote;

    /** 生成时刻 */
    private OffsetDateTime createdAt;
}
