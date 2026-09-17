package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.billing.enums.PayerType;
import com.fuyun.billing.enums.SettlementStatus;
import com.fuyun.billing.enums.VisitType;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 结算单实体（billing.settlement，FU-M13-03）：医保拆分五列全部按预结算回执落库（红线 1
 * 本地不自行计算基金拆分）；SETTLED 后只读（红线 2）。payment_details 存支付明细 JSON 文本
 * （金额勾稽第三层校验源）。线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("billing.settlement")
public class Settlement {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 结算编号（S+服务端生成序列号；幂等锚点=本列终态） */
    private String settleNo;

    /** 患者主索引 */
    private Long patientId;

    /** CF-3 定长就诊号 */
    private String visitId;

    /** 结算类型 OUT/IN（出院结算并入 IN+费用期区分，PEIS 预留） */
    private VisitType settleType;

    /** 支付类型（SELF_PAY/CITY_INS/PROV_INS/OUTSIDE_INS/COMM_INS） */
    private PayerType payerType;

    /** 费用期起 */
    private OffsetDateTime feeStart;

    /** 费用期止 */
    private OffsetDateTime feeEnd;

    /** 应结总额（分） */
    private Long totalAmount;

    /** 统筹支付（分，医保回执） */
    private Long pooledAmount;

    /** 个账支付（分） */
    private Long acctPayAmount;

    /** 自付（分，先自付+比例自付） */
    private Long selfPayAmount;

    /** 自费（分，目录外） */
    private Long selfExpenseAmount;

    /** 先自付（分） */
    private Long preSelfPayAmount;

    /** 支付方式明细 JSON 文本（[{method,amount,channelRef}]，支付合计=总额勾稽） */
    private String paymentDetails;

    /** 医保中心结算流水号（回执，可空） */
    private String insSettleNo;

    /** 回执原文引用（insurance_call_log.id，红线 5，可空） */
    private Long insReceiptRef;

    /** 目录版本（异地「就医地目录」留痕，可空） */
    private String catalogVersion;

    /** 结算状态机（SettlementStatus 六值） */
    private SettlementStatus status;

    /** 正式结算时刻（可空） */
    private OffsetDateTime settledAt;

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
