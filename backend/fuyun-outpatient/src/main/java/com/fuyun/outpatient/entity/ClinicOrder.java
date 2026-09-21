package com.fuyun.outpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.outpatient.enums.OrderStatus;
import com.fuyun.outpatient.enums.OrderType;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 申请单主单实体（outpatient.clinic_order，M03 医生站开单）：order.created（id 23）发布载体，
 * order_no=OP+yyyyMMdd+6 位流水业务号即 billing sourceRef（主控裁决 3）；状态机 CREATED→
 * PENDING_FEE→CHARGED（IN_EXECUTION/COMPLETED 为 P3 声明态）→CANCELLED。资金无涉红线（裁决 7）：
 * 零金额列，fee_settlement_id 仅存结算回填锚。线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("outpatient.clinic_order")
public class ClinicOrder {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 申请单业务号（OP+yyyyMMdd+6 位流水，uk_order_no 唯一；billing sourceRef 直取） */
    private String orderNo;

    /** 就诊号（uk_visit_id 同源，开单前置 visit 非终态守卫） */
    private String visitId;

    /** 患者主索引（visit 同源冗余，order.created 载荷直取） */
    private Long patientId;

    /** 单据类型（OrderType：EXAM/LAB/TREATMENT/DISPOSAL/MATERIAL/RX_REF） */
    private OrderType orderType;

    /** 外部单据引用（rx_ref=M06 rxNo；P1 仅 RX_REF 写），可空 */
    private String extRef;

    /** 开单医生 id（执业授权强校验通过者） */
    private String orderDoctorId;

    /** 执行有效期（P1 为空，执行域随 P3），可空 */
    private OffsetDateTime validTo;

    /** 申请单状态机（OrderStatus：CREATED/PENDING_FEE/CHARGED/IN_EXECUTION（声明态）/COMPLETED（声明态）/CANCELLED） */
    private OrderStatus status;

    /** 结算单 id（M13 回填锚），可空 */
    private Long feeSettlementId;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护（触发器刷新） */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入 */
    private String createdBy;

    /** 审计列：操作人应用层注入 */
    private String updatedBy;

    /** 逻辑删标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Short deleted;
}
