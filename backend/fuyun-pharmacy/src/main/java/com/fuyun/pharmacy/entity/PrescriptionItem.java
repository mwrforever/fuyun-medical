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
 * 处方明细实体（pharmacy.prescription_item，V701）：计费行快照载体（item_code+数量+用法摘要
 * 随 created 事件携带）。明细只读（Spec :107：改方=驳回后重新开立新版本），
 * returned_quantity/status 由退药链回写。
 */
@Getter
@Setter
@TableName("pharmacy.prescription_item")
public class PrescriptionItem {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属处方 id */
    private Long prescriptionId;

    /** 药品引用（drug 字典） */
    private Long drugId;

    /** 院内码快照 */
    private String drugCode;

    /** 计费行快照·收费项目 code（M13 权威引用，NULL 药品开方即拒） */
    private String itemCode;

    /** 数量（计费与发药共用口径，>0） */
    private BigDecimal quantity;

    /** 单位 */
    private String unit;

    /** 单次剂量 */
    private String singleDose;

    /** 给药途径（M01 字典 code；须 ∈ drug.route_codes） */
    private String routeCode;

    /** 用药频次（M01 字典 code，PR-4 仅非空校验） */
    private String frequency;

    /** 用药天数 */
    private Integer days;

    /** 用法备注 */
    private String usageNote;

    /** 用法摘要（created 事件计费行携带，服务端拼装） */
    private String usageSummary;

    /** 明细级皮试要求快照 */
    private Boolean skinTestFlag;

    /** 已退数量（退药回写） */
    private BigDecimal returnedQuantity;

    /** 明细状态 NORMAL/CANCELLED（发药中明细退场） */
    private String status;

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
