package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.inpatient.handler.JsonbStringTypeHandler;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 出院申请实体（inpatient.discharge_request，V907）——「预出院/明日出院」实践载体：出院申请
 * 单事务完成在途清理编排（长期医嘱批量停嘱/临时追踪清单/未执行计划作废，清理结果快照入
 * clearance_result）与费用预审（结清 READY/欠费 BLOCKED 附欠费额快照——billing 权威数据的
 * 回显，请求面零金额输入 GC18）。uk_visit_active 部分唯一兜底一 visit 至多一条在途申请
 * （REQUESTED/READY/BLOCKED）。结算完成标记与挂账审批放行由 BillingEventListener 消费
 * billing.settlement.completed / billing.arrears.approved 驱动 CAS 落值（幂等三段式）。
 * autoResultMap 必开——clearance_result 经 JsonbStringTypeHandler 逐列挂载（读侧映射需要）。
 */
@Getter
@Setter
@TableName(value = "inpatient.discharge_request", autoResultMap = true)
public class DischargeRequest {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 出院申请单号（DC+yyyyMMdd+5 位流水，InpatientSeqGate.nextNo("DC") 签发；uk 兜底发号幂等） */
    private String requestNo;

    /** 住院就诊主键（inpatient_visit.id——与 medical_order.visit_id 同口径，禁 I 型号入库） */
    private Long visitId;

    /** 患者主索引（随访计划与事件载荷取数面） */
    private Long patientId;

    /** 申请医生（员工 ID string，OperatorContextHolder 解析） */
    private String requesterId;

    /** 申请时点（服务器时钟） */
    private OffsetDateTime requestedAt;

    /** 预出院时间（「明日出院」预出院模式载体，可空=即时出院） */
    private OffsetDateTime expectDischargeAt;

    /** 离院方式（DischargeWay 病案首页代码；离院确认时誊写至 inpatient_visit.discharge_way） */
    private String dischargeWay;

    /**
     * 在途清理结果快照（JSONB 文本：
     * {stoppedLongCount:长期停嘱数, trackedOrders:[{orderNo,orderClass,status}], cancelledPlanCount:计划作废数}；
     * jsonb TypeHandler 挂载见类注——无合法停嘱边的停留医嘱入 trackedOrders 供人工处置追踪）
     */
    @TableField(value = "clearance_result", typeHandler = JsonbStringTypeHandler.class)
    private String clearanceResult;

    /** 欠费额快照（分；预审 BLOCKED 时=max(0,未结清合计-押金余额)，READY 为 NULL——GC18 快照回显） */
    private Long arrearsAmount;

    /** 出院结算完成时点（消费 billing.settlement.completed 落标记——离院确认双条件之一；IS NULL 限定 CAS 幂等） */
    private OffsetDateTime settlementCompletedAt;

    /** 挂账审批单号（消费 billing.arrears.approved 由 BLOCKED→READY 时记录——放行凭证留痕） */
    private String approvalNo;

    /** 状态 code（DischargeRequestStatus 五值） */
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
