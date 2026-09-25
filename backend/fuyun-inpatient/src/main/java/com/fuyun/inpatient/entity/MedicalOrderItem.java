package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 住院医嘱明细实体（inpatient.medical_order_item，V904）——与主表同事务落库的行项目：
 * item_type=DRUG 行参与开立校验第二层过敏拦截（item_code 命中过敏项药物 code 即拒）与
 * 第三层剂量/途径必填校验；name_snapshot 名称快照供计价/审方免回查（药品通用名可入事件
 * 载荷——非敏感项）；fee_priced/fee_stopped 计费回执两列为 M13 停嘱对账面（本域只写
 * 默认 false，回执刷新归计费联动）。
 */
@Getter
@Setter
@TableName("inpatient.medical_order_item")
public class MedicalOrderItem {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属医嘱主键（medical_order 1:N medical_order_item） */
    private Long orderId;

    /** 行序号（同一医嘱内 1 起递增；成组医嘱组内序号） */
    private Integer itemSeq;

    /** 延续标志（成组医嘱组内延续执行标记） */
    private Boolean continueFlag;

    /** 行项目类型（与 OrderType 同词表形态：DRUG 药品/LAB 检验/EXAM 检查等；过敏拦截仅判 DRUG） */
    private String itemType;

    /** 项目编码（药品/检验/检查等项目字典编码；过敏拦截匹配键） */
    private String itemCode;

    /** 项目名称快照（开立时点誊写；计价/审方免回查） */
    private String nameSnapshot;

    /** 剂量（数值字符串如 0.5；药品项必填——IP-1011 校验面） */
    private String dosage;

    /** 剂量单位（如 g/ml；药品项必填——IP-1011 校验面） */
    private String dosageUnit;

    /** 给药途径（M01 medication.route 字典 code；药品项必填——IP-1011 校验面） */
    private String route;

    /** 滴速（如 40 滴/分；静滴类医嘱携带） */
    private String dripRate;

    /** 数量（正数；事件载荷 quantity 以 DECIMAL string 承载） */
    private BigDecimal quantity;

    /** 执行科室编码（M01 组织机构 code；LIS/PACS 等执行归口） */
    private String execDeptId;

    /** 皮试标记（药品项：执行前须皮试） */
    private Boolean skinTestFlag;

    /** 抢救口头医嘱补录标记（口头医嘱执行后补录确认，Task 6 oral-confirm 消费） */
    private Boolean oralFlag;

    /** 计费回执标记：M13 已计价（对账用；开立默认 false，billing 回执面刷新） */
    private Boolean feePriced;

    /** 计费截断回执标记：M13 已按停嘱时点截断计费（对账用；开立默认 false） */
    private Boolean feeStopped;

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
