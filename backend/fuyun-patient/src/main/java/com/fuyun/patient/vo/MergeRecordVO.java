package com.fuyun.patient.vo;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 合并记录出参（发起/审批/拆分响应与回显载体；pre_snapshot 全文不出参——审计经库内查询）。
 */
@Getter
@Setter
public class MergeRecordVO {

    /** 合并记录 id */
    private Long id;

    /** 主档 id */
    private Long survivorPatientId;

    /** 从档 id */
    private Long mergedPatientId;

    /** 合并原因 */
    private String mergeReason;

    /** 状态 PROCESSING/COMPLETED/FAILED/REVERSED */
    private String status;

    /** 经办人 */
    private String operator;

    /** 审批人（审批后非空） */
    private String approvedBy;

    /** 完成时刻 */
    private OffsetDateTime completedAt;

    /** 拆分时刻 */
    private OffsetDateTime reversedAt;

    /** 拆分原因 */
    private String reverseReason;
}
