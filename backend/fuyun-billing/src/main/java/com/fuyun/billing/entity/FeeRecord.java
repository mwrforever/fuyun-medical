package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.billing.enums.ChargeSource;
import com.fuyun.billing.enums.ExecOccupyStatus;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.enums.VisitType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 费用明细实体（billing.fee_record，M13 全模块核心表）：快照列组冗余冻结列（与版本表不一致时
 * 以本表为准，历史正确性优先）；billing_key 计费唯一键为重复计费硬防线。线程安全：可变实体
 * 仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("billing.fee_record")
public class FeeRecord {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 费用编号（F+服务端生成序列号；幂等回显锚点，非资金键） */
    private String feeNo;

    /** 患者主索引（M02） */
    private Long patientId;

    /** CF-3 定长就诊号（O|I+8 位日期+5 位流水） */
    private String visitId;

    /** 就诊类型 OUT/IN/PEIS */
    private VisitType visitType;

    /** 收费项目 id */
    private Long chargeItemId;

    /** 项目名称快照 */
    private String itemNameSnapshot;

    /** 单价快照（分） */
    private Long unitPriceSnapshot;

    /** 数量（DECIMAL(12,3)，>0） */
    private BigDecimal quantity;

    /** 金额（分，=单价×数量 服务端 HALF_UP 取整） */
    private Long amount;

    /** 清单费用大类快照（一日清单/结算清单分组） */
    private String feeCategorySnapshot;

    /** 计费来源（ORDER_LINKED/EXEC_LINKED/DAY_CUTOVER/MANUAL/PEIS） */
    private ChargeSource chargeSource;

    /** 来源单据引用（医嘱/执行单/申请单号/手工=操作者工号，红线 3 可追溯） */
    private String sourceRef;

    /** 计费点（TriggerType 事件驱动分流） */
    private TriggerType triggerPoint;

    /** 计费日（计费唯一键第四要素） */
    private LocalDate billingDate;

    /** 医保对照编码快照（无有效对照=NULL，仅自费） */
    private String nhsaCodeSnapshot;

    /** 目录版本快照（可空） */
    private String catalogVersionSnapshot;

    /** 先自付比例快照（DECIMAL(5,4)，可空） */
    private BigDecimal selfPayRatioSnapshot;

    /** 限价快照（分，可空） */
    private Long limitPriceSnapshot;

    /** 计费唯一键（服务端拼装 patient_id|source_ref|trigger_point|charge_item_id|billing_date） */
    private String billingKey;

    /** 价格版本号快照（快照不漂移 IT 锚点） */
    private Integer priceVersion;

    /** 结算单 id（未结算 NULL） */
    private Long settlementId;

    /** 执行占用状态（NONE/DISPENSED/EXECUTED/UPLOADED，退费硬前置） */
    private ExecOccupyStatus execOccupyStatus;

    /** 费用状态机（FeeStatus 八值） */
    private FeeStatus status;

    /** 手工计费操作者（charge_source=MANUAL 必填，服务层守卫） */
    private String operator;

    /** 手工计费理由（同上必填） */
    private String manualReason;

    /** 计费时刻 */
    private OffsetDateTime chargedAt;

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
