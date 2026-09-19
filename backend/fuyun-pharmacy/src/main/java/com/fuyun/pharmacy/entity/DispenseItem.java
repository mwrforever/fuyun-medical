package com.fuyun.pharmacy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 调剂明细实体（pharmacy.dispense_item，V703）：追溯码逐码采集落行级（医保「无码不结」与
 * 防回流核验依据，Spec :112）。应发数=处方数量入队，实发/退发数与批次随三段/退药回写；
 * traceCodes 为 JSON 数组文本（ObjectMapper 读写）。
 */
@Getter
@Setter
@TableName("pharmacy.dispense_item")
public class DispenseItem {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属调剂单 id */
    private Long dispenseId;

    /** 处方明细 id（快照回溯锚） */
    private Long prescriptionItemId;

    /** 药品引用（drug 字典） */
    private Long drugId;

    /** 计费行快照·收费项目 code（M13 权威引用） */
    private String itemCode;

    /** 应发数（处方数量入队，>0） */
    private BigDecimal requestedQty;

    /** 实发数（发药签名回写） */
    private BigDecimal issuedQty;

    /** 退药数（退药受理回写） */
    private BigDecimal returnedQty;

    /** 选批批次 id（FEFO 单批足量，拆批分配随 P3） */
    private Long batchId;

    /** 选批批次号 */
    private String batchNo;

    /** 追溯码逐码采集（JSON 数组文本） */
    private String traceCodes;

    /** 明细状态 NORMAL/CANCELLED（发药中明细退场） */
    private String itemStatus;

    /** 创建时刻 */
    private OffsetDateTime createdAt;

    /** 更新时刻 */
    private OffsetDateTime updatedAt;

    /** 创建者 */
    private String createdBy;

    /** 更新者 */
    private String updatedBy;

    /** 逻辑删标记 */
    @TableLogic
    private Integer deleted;
}
