package com.fuyun.inpatient.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.dto.StandbyTriggerRequest;
import com.fuyun.inpatient.service.OrderTransferService;
import com.fuyun.inpatient.vo.OrderPlanVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行计划端点（/api/v1/inpatient/order-plans）——FU-M04-06 上（04-inpatient Spec §6）：
 * 计划日视图分页查询与嘱托按需触发单次计划（执行回签端点 {no}/execute-confirm 归 Task 8
 * W-33 落码，本控制器不含）。controller 禁业务逻辑与事务（A.1-8）；写端点挂 WRITE 审计（GC22）。
 * 类级 @RequestMapping 不承载（方法级全路径自文档，OrderController 同款）。
 */
@Tag(name = "M04 执行计划", description = "执行计划日视图分页/嘱托按需触发单次计划（多次触发多次台账）")
@RestController
@RequiredArgsConstructor
@Validated
public class OrderPlanController {

    private final OrderTransferService orderTransferService;

    /**
     * 执行计划日视图：按计划日期（当日窗口）分页，病区可叠加过滤，计划时点升序。
     *
     * @param date   计划日期（必填过滤键——计划视图以日为轴，ISO yyyy-MM-dd），非空；来源：查询参数
     * @param wardId 病区过滤（可空=全部病区），可空；来源：查询参数
     * @param page   页码（0 基），缺省 0
     * @param size   单页条数（1-200），缺省 20
     * @return 执行计划分页出参
     * @throws com.fuyun.common.exception.BizException IP-1022（400 日期缺失）；IP-1009/IP-1007
     *                 （404 关联行缺失——数据不一致）
     */
    @Operation(summary = "执行计划日视图分页（日期窗口+病区过滤）", operationId = "listOrderPlans")
    @GetMapping("/api/v1/inpatient/order-plans")
    public PageResult<OrderPlanVO> list(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value = "wardId", required = false) String wardId,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return orderTransferService.listPlans(date, wardId, page, size);
    }

    /**
     * 嘱托按需触发单次计划：长期备用嘱生成当次计划实例（多次触发多次台账，不重复计价由
     * M13 唯一键兜底）；医嘱头状态不迁移（回签面推进）。
     *
     * @param req 嘱托触发出入参（嘱托医嘱号），非空；来源：护士站嘱托医嘱卡触发
     * @return 当次生成的计划出参集（按明细行一至多条）
     * @throws com.fuyun.common.exception.BizException IP-1009/IP-1022/IP-1010/IP-1023（服务接口注全清单）
     */
    @Operation(summary = "嘱托按需触发单次计划（多次触发多次台账）", operationId = "triggerStandbyOrderPlan")
    @PostMapping("/api/v1/inpatient/order-plans/standby-trigger")
    @AuditLog(actionType = AuditActionType.WRITE)
    public List<OrderPlanVO> standbyTrigger(@Valid @RequestBody StandbyTriggerRequest req) {
        return orderTransferService.standbyTrigger(req.orderNo());
    }
}
