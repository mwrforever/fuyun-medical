package com.fuyun.pharmacy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 住院摆药计划实体（pharmacy.dispense_plan，V1110）：住院医嘱→摆药计划载体（M06 药师摆药
 * 工作台底座）。生成前置=order_medication 快照存在且 review_task=APPROVED（PH-1025）；
 * 状态机 CREATED→PICKING→PICKED→CHECKED→DELIVERED（住院链止于 DELIVERED 病区签收），
 * CREATED/PICKING→CANCELLED（停嘱/出院终清联动）。uk_dispense_plan_order_time
 * （m04_order_no+plan_time）承载「同医嘱同给药时点一计划」排程防重（generate 幂等锚）。
 * deliver 配送交接为 CHECKED 态内 issued_at 时间线半步（不迁移状态——Spec 状态机
 * CHECKED→DELIVERED 直迁，receive 病区签收才是 CAS 迁移点）。
 */
@Getter
@Setter
@TableName("pharmacy.dispense_plan")
public class DispensePlan {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 摆药计划号（DP+yyyyMMdd+5 位流水，PharmacySeqGate 签发；uk 唯一） */
    private String planNo;

    /** 住院医嘱号（M04 medical_order.order_no；生成来源医嘱引用与终清作废定位键） */
    private String m04OrderNo;

    /** 住院就诊号（I 型 14 位，M02 结构规范） */
    private String visitId;

    /** 患者主索引（M02） */
    private Long patientId;

    /** 病区编码（目标病区；全仓 ward_id 64 同宽口径） */
    private String wardId;

    /** 计划类型：SINGLE_DOSE 单剂量 / PIVAS 静配 / WHOLE 整包（生成时按用法判定） */
    private String planType;

    /** 给药时点（排程基准；uk_dispense_plan_order_time 唯一维度第二列） */
    private OffsetDateTime planTime;

    /** 计划状态（CREATED/PICKING/PICKED/CHECKED/DELIVERED/CANCELLED；V1110 列注释词表） */
    private String status;

    /** 排批号（PIVAS 给药时间分批 DPB+yyyyMMdd+3 位；非 PIVAS 计划为 NULL） */
    private String pivasBatchNo;

    /** 贴签核对标记（PIVAS 链 verify 贴签核对置 true；打印归 M01 降级注记——贴签内容经 label 数据面出） */
    private Boolean labelPrinted;

    /** 摆药师员工 ID（pick 动作主体；生成时未定为 NULL） */
    private Long pickedBy;

    /** 核对药师员工 ID（verify 双人核对第二签；核对通过回写） */
    private Long verifiedBy;

    /** 出库时点（deliver 配送交接时间线半步承载列——CHECKED 态内置位不迁状态） */
    private OffsetDateTime issuedAt;

    /** 病区签收时点（receive CHECKED→DELIVERED CAS 随行落值，M05 签收衔接消费） */
    private OffsetDateTime deliveredAt;

    /** 病区签收人员工 ID（receive 请求体显式携带） */
    private Long receivedBy;

    /** 撤销原因（医嘱停止/出院终清联动作废时必填留痕） */
    private String cancelReason;

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
