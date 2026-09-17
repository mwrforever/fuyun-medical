package com.fuyun.billing.controller;

import com.fuyun.billing.dto.CompensationRetryRequest;
import com.fuyun.billing.dto.CredentialVerifyRequest;
import com.fuyun.billing.dto.CredentialVerifyResponse;
import com.fuyun.billing.entity.InsuranceCallLog;
import com.fuyun.billing.gateway.InsuranceGateway;
import com.fuyun.billing.service.IInsuranceCallLogService;
import com.fuyun.billing.vo.InsuranceCallLogVO;
import com.fuyun.common.web.PageResult;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医保基线端点（FU-M13-05 接口位，已裁决 3 口径：演示走模拟通道，真实通道 P5 替换网关实现后
 * 契约零改）：门诊登记/费用上传/撤销/调用留痕查询/补偿重试/电子凭证核验。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：端点为网关/留痕服务的薄透传位；撤销与补偿重试端点
 * 挂 WRITE 审计（@AuditLog 注解 + AuditLogAspect 上下文内拦截落 system.audit_log）。调用口径
 * 同 Global Constraints 事务红线：模拟适应器零 IO 事务内直调合规，P5 真实通道改事务外两段式。
 */
@Tag(name = "billing-insurance", description = "M13 医保基线接口位（模拟通道，FU-M13-05）")
@RestController
@RequestMapping("/api/v1/billing")
@Validated
public class InsuranceController {

    private final InsuranceGateway insuranceGateway;

    private final IInsuranceCallLogService callLogService;

    /**
     * 构造器注入（A.1-7），装配归 BillingWebConfig @Import。
     *
     * @param insuranceGateway 医保出站网关（模拟适应器零 IO），非空
     * @param callLogService   医保调用留痕服务（留痕查询/补偿重试），非空
     */
    public InsuranceController(InsuranceGateway insuranceGateway, IInsuranceCallLogService callLogService) {
        this.insuranceGateway = insuranceGateway;
        this.callLogService = callLogService;
    }

    /**
     * 门诊登记（POST /insurance/register?visitId=&patientId=，基线交易码 2001 接口位）。
     *
     * @param visitId   CF-3 就诊号（请求参数）；来源：收费员工作站登记动作
     * @param patientId 患者主索引（请求参数）
     * @return 中心登记流水号；200
     */
    @Operation(summary = "医保门诊登记（2001 接口位）", operationId = "registerInsuranceVisit")
    @PostMapping("/insurance/register")
    public String register(@RequestParam String visitId, @RequestParam long patientId) {
        return insuranceGateway.register(visitId, patientId);
    }

    /**
     * 费用上传（POST /insurance/fee-uploads?visitId=，基线交易码 2101 接口位；请求体=费用行 id 列表）。
     *
     * @param visitId CF-3 就诊号（请求参数）
     * @param feeIds  费用行 id 列表（请求体 JSON 数组）
     * @return 上传行数（中心受理口径）；200
     */
    @Operation(summary = "医保费用上传（2101 接口位）", operationId = "uploadInsuranceFees")
    @PostMapping("/insurance/fee-uploads")
    public int feeUpload(@RequestParam String visitId, @RequestBody List<Long> feeIds) {
        return insuranceGateway.feeUpload(visitId, feeIds);
    }

    /**
     * 医保撤销（POST /insurance/reverse?centerSerialNo=&idempotencyKey=，基线交易码 2104；WRITE 审计）。
     *
     * @param centerSerialNo 原中心流水号（预结算/结算回执）
     * @param idempotencyKey 撤销幂等键
     * @return 撤销中心流水号；200
     */
    @Operation(summary = "医保撤销（2104 接口位）", operationId = "reverseInsuranceSettlement")
    @PostMapping("/insurance/reverse")
    @AuditLog(actionType = AuditActionType.WRITE)
    public String reverse(@RequestParam String centerSerialNo, @RequestParam String idempotencyKey) {
        return insuranceGateway.reverse(centerSerialNo, idempotencyKey);
    }

    /**
     * 调用留痕分页（GET /insurance/call-logs?visitId=&page=&size=，运营排查与补偿工作台数据源；纯读）。
     *
     * @param visitId 就诊号过滤，可空=全部
     * @param page    页码（0 基），缺省 0
     * @param size    单页条数（1-200），缺省 20
     * @return 留痕行分页出参；200
     */
    @Operation(summary = "医保调用留痕分页查询", operationId = "listInsuranceCallLogs")
    @GetMapping("/insurance/call-logs")
    public PageResult<InsuranceCallLogVO> callLogs(
            @RequestParam(value = "visitId", required = false) String visitId,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        // 实体禁直出：查询结果逐行经静态工厂转 VO（出网边界唯一出口）
        PageResult<InsuranceCallLog> result = callLogService.page(visitId, page, size);
        return PageResult.of(
                result.content().stream().map(InsuranceCallLogVO::from).toList(),
                result.page(),
                result.size(),
                result.total());
    }

    /**
     * 补偿重试（POST /insurance/call-logs/{id}/compensation，TIMEOUT/FAILED→COMPENSATED；
     * BILL-1026 状态守卫归服务层；WRITE 审计）。
     *
     * @param id  留痕行 id（路径变量）
     * @param req 补偿重试请求（@Valid，结论必填留痕）；来源：运维/收费组长录入
     * @return 204 无体（状态机迁移至 COMPENSATED）
     * @throws com.fuyun.common.exception.BizException BILL-1025（404 缺行）/
     *                 BILL-1026（409 非 TIMEOUT/FAILED 行拒重试）
     */
    @Operation(summary = "医保调用补偿重试（BILL-1026 状态守卫）", operationId = "retryInsuranceCompensation")
    @PostMapping("/insurance/call-logs/{id}/compensation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void compensate(@PathVariable long id, @Valid @RequestBody CompensationRetryRequest req) {
        callLogService.compensate(id, req.note());
    }

    /**
     * 电子凭证核验（POST /insurance/credential 接口位）：@Valid 边界拒空 + 服务层 BILL-1024
     * 双守卫（禁空凭证静默放行）；P5 真实扫码核验替换网关实现后契约零改。
     *
     * @param req 凭证核验请求（@Valid 声明式校验）；来源：前端扫码组件
     * @return 核验出参 {"authSerialNo":"SIM-AUTH-…"}；200
     * @throws com.fuyun.common.exception.BizException BILL-1024（502 凭证令牌空/空白显式拒）
     */
    @Operation(summary = "医保电子凭证核验（接口位，模拟形态）", operationId = "verifyInsuranceCredential")
    @PostMapping("/insurance/credential")
    public CredentialVerifyResponse credential(@Valid @RequestBody CredentialVerifyRequest req) {
        return new CredentialVerifyResponse(insuranceGateway.authenticate(req.ecToken()));
    }
}
