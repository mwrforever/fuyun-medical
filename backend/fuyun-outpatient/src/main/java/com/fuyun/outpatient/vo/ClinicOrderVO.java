package com.fuyun.outpatient.vo;

import com.fuyun.outpatient.enums.OrderStatus;
import com.fuyun.outpatient.enums.OrderType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 申请单出参（POST /visits/{visitId}/orders 直出、GET /orders、POST /orders/{no}/cancel 共用）：
 * patientId 等长整型经全局 Long→String 定制以 JSON 字符串输出（A.3-8）；quantity 为 DECIMAL
 * string 透传（D-18 同源，禁数值化）。
 *
 * @param id              申请单主键；来源：落库回填
 * @param orderNo         申请单业务号（OP+yyyyMMdd+6 位流水，billing sourceRef 同值）
 * @param visitId         就诊号（CF-3 冻结）
 * @param patientId       患者主索引
 * @param orderType       单据类型 code（EXAM/LAB/TREATMENT/DISPOSAL/MATERIAL/RX_REF）
 * @param extRef          外部单据引用（rx_ref=M06 rxNo；P1 仅 RX_REF 写），可空
 * @param orderDoctorId   开单医生 id
 * @param validTo         执行有效期（P1 为空），可空
 * @param status          申请单状态 code（CREATED/PENDING_FEE/CHARGED/IN_EXECUTION（声明态）/COMPLETED（声明态）/CANCELLED）
 * @param dispenseStatus  发药回流镜像（DISPENSED/PART_RETURNED/FULL_RETURNED；null=未发药；M06
 *                        发药/退药事件经 CAS 镜像回写，D-3 补投影）
 * @param feeSettlementId 结算单 id（M13 回填锚；未结算为 null），可空
 * @param items           计费行明细清单（itemCode/quantity/usageSummary），非空
 */
public record ClinicOrderVO(
        Long id,
        String orderNo,
        String visitId,
        Long patientId,
        OrderType orderType,
        String extRef,
        String orderDoctorId,
        OffsetDateTime validTo,
        OrderStatus status,
        String dispenseStatus,
        Long feeSettlementId,
        List<Item> items) {

    /**
     * 计费行出参（clinic_order_item 投影，与 OrderCreatedPayload.Line 同源口径）。
     *
     * <p>springdoc 注册名显式化（D-4，backend 首例）：嵌套 record 简名 Item 与
     * PrescriptionOpenRequest.Item 在 /v3/api-docs 同名注册坍缩（后者 drugId 形态覆盖前者，
     * itemCode/quantity/usageSummary 形态在生成物中整体丢失），经 @Schema(name=...) 分立注册名；
     * 不动类名、不影响运行时 Jackson 序列化形态（springdoc 仅文档链路）。
     *
     * @param itemCode     项目编码（M13 物价库 code）
     * @param quantity     数量（DECIMAL string 透传）
     * @param usageSummary 用法摘要，可空
     */
    @Schema(name = "ClinicOrderItem")
    public record Item(String itemCode, String quantity, String usageSummary) {}
}
