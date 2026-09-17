package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.billing.enums.InsuranceCallStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 医保业务调用日志实体（billing.insurance_call_log，方案 3.2 业务级留痕）：悬挂确认/冲正/
 * 补偿驱动数据源；摘要列脱敏落库（禁完整明文入日志，等保要求）。线程安全：可变实体仅
 * service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("billing.insurance_call_log")
public class InsuranceCallLog {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 基线版交易码（2001 门诊登记/2002 入院办理/2101 费用上传/2102 预结算/2103 结算/2104 撤销） */
    private String txnCode;

    /** CF-3 就诊号（可空） */
    private String visitId;

    /** 结算单 id（可空） */
    private Long settlementId;

    /** 请求摘要（脱敏，禁完整明文入日志） */
    private String requestDigest;

    /** 应答摘要/回执原文引用摘要（可空） */
    private String responseDigest;

    /** 中心流水号（可空） */
    private String centerSerialNo;

    /** 中心结果码（模拟=0000 成功，可空） */
    private String resultCode;

    /** 中心结果消息（可空） */
    private String resultMsg;

    /** 调用耗时（毫秒，可空） */
    private Long durationMs;

    /** 调用状态 INIT/SENT/SUCCESS/FAILED/TIMEOUT/COMPENSATED/WAIVED */
    private InsuranceCallStatus status;

    /** 补偿/核销结论（WAIVED 必填，可空） */
    private String compensateNote;

    /** 全链路 traceId（可空） */
    private String traceId;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护 */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入 */
    private String createdBy;

    /** 审计列 */
    private String updatedBy;

    /** 逻辑删标记（@TableLogic 全局配置） */
    @TableLogic
    private Short deleted;
}
