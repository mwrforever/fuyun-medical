package com.fuyun.inpatient.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.dto.OrderCreateRequest;
import com.fuyun.inpatient.enums.OrderClass;
import com.fuyun.inpatient.service.MedicalOrderService;
import com.fuyun.inpatient.vo.MedicalOrderVO;
import com.fuyun.inpatient.vo.OrderDetailVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医嘱开立端点（/api/v1/inpatient/visits/{visitId}/orders + /api/v1/inpatient/orders）——
 * FU-M04-04 三端点（04-inpatient Spec §6）：开立（四层校验→CREATED→order.created 子键路由）/
 * 分页查询/详情（含明细行）。controller 禁业务逻辑与事务（A.1-8）：守卫链/状态机/事件发布
 * 全归服务层；写端点挂 WRITE 审计。停嘱/作废/撤回/重整四控制端点归 Task 6 增补。
 * 类级 @RequestMapping 不承载（方法级全路径自文档，AdmissionController 同款）。
 */
@Tag(name = "M04 医嘱开立", description = "医嘱开立（四层校验）/就诊医嘱分页/医嘱详情")
@RestController
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final MedicalOrderService medicalOrderService;

    /**
     * 医嘱开立：在院校验→四层校验（执业授权/过敏/明细频次/嘱托限定）→CREATED 落库→
     * 发布 inpatient.order.created（routing key 携带类型子键）。
     *
     * @param visitId 住院就诊号（I 型 14 位，路径参数），非空
     * @param req     开立入参（头+项列表），非空；来源：医生站开单
     * @return 开立后医嘱出参（status=CREATED）
     * @throws com.fuyun.common.exception.BizException IP-1007/IP-1008/IP-1012/IP-1013/IP-1011/
     *                 IP-1021/IP-1022/IP-1023（服务接口注全清单）
     */
    @Operation(summary = "医嘱开立（四层校验→CREATED→order.created 子键路由）", operationId = "createMedicalOrder")
    @PostMapping("/api/v1/inpatient/visits/{visitId}/orders")
    @AuditLog(actionType = AuditActionType.WRITE)
    public MedicalOrderVO create(
            @PathVariable("visitId") @NotBlank String visitId, @Valid @RequestBody OrderCreateRequest req) {
        return medicalOrderService.create(visitId, req);
    }

    /**
     * 就诊医嘱分页查询（开立时间倒序；分类可叠加过滤）。
     *
     * @param visitId 住院就诊号（I 型 14 位，必填过滤键——住院医嘱视图以就诊为轴），非空；来源：查询参数
     * @param clazz   医嘱分类过滤（LONG/STAT；可空=全部分类），可空；来源：查询参数
     * @param page    页码（0 基），缺省 0
     * @param size    单页条数（1-200），缺省 20
     * @return 医嘱分页出参
     * @throws com.fuyun.common.exception.BizException IP-1007（404 就诊不存在）
     */
    @Operation(summary = "就诊医嘱分页（开立时间倒序）", operationId = "listMedicalOrders")
    @GetMapping("/api/v1/inpatient/orders")
    public PageResult<MedicalOrderVO> list(
            @RequestParam("visitId") @NotBlank String visitId,
            @RequestParam(value = "class", required = false) OrderClass clazz,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return medicalOrderService.list(visitId, clazz, page, size);
    }

    /**
     * 医嘱详情（头 + 明细行全集，闭环追溯取数入口）。
     *
     * @param no 医嘱号（路径参数），非空
     * @return 医嘱详情出参（含项）
     * @throws com.fuyun.common.exception.BizException IP-1009（404 医嘱不存在）/IP-1007（404
     *                 关联就诊不存在）
     */
    @Operation(summary = "医嘱详情（含明细行）", operationId = "getMedicalOrder")
    @GetMapping("/api/v1/inpatient/orders/{no}")
    public OrderDetailVO detail(@PathVariable("no") @NotBlank String no) {
        return medicalOrderService.detail(no);
    }
}
