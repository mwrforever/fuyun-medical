package com.fuyun.inpatient.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.dto.AdmissionCreateRequest;
import com.fuyun.inpatient.dto.AdmissionScheduleRequest;
import com.fuyun.inpatient.dto.VisitRegisterRequest;
import com.fuyun.inpatient.dto.WardAdmitRequest;
import com.fuyun.inpatient.enums.AdmissionStatus;
import com.fuyun.inpatient.service.AdmissionService;
import com.fuyun.inpatient.vo.AdmissionVO;
import com.fuyun.inpatient.vo.ArrearsAlarmVO;
import com.fuyun.inpatient.vo.InpatientVisitVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入院登记端点（/api/v1/inpatient/admissions + /api/v1/inpatient/visits）——FU-M04-01 六端点
 * （04-inpatient Spec §6）：住院证登记/候床队列/预约入院/作废/登记确认（同事务签发 I 型
 * visit_id 红线方法）/入科确认；Task 10 追加病区欠费清单查询（FU-M04-08 住院计费入口——
 * arrears_flag 本地标识聚合，患者摘要脱敏出网）。
 * controller 禁业务逻辑与事务（A.1-8）：守卫链/状态机/事件发布全归服务层；写端点挂 WRITE
 * 审计（@AuditLog 注解 + AuditLogAspect 上下文内拦截落 system.audit_log）。
 * 类级 @RequestMapping 不承载（方法级全路径自文档，WardController 同款）。
 * 床位联动三处已随 Task 4 V903 bed 落地（schedule 预占/cancel 释放/admit-ward 占床——服务层
 * BedService 同事务联动）；床位域端点归 BedController。
 */
@Tag(name = "M04 入院登记", description = "住院证登记/候床队列/预约/作废/登记确认/入科确认")
@RestController
@RequiredArgsConstructor
@Validated
public class AdmissionController {

    private final AdmissionService admissionService;

    /**
     * 住院证登记（登记即建单入 WAITING 候床队列）。
     *
     * @param req 登记入参（来源/类型词表、患者主索引、诊断摘要等），非空；来源：医生站/登记台开证
     * @return 建单出参（status=WAITING）
     * @throws com.fuyun.common.exception.BizException IP-1022（400 词表外/转诊缺引用）/
     *                 IP-1003（409 档案冻结）/ IP-1023（409 证号冲突）/ PAT-1001（404 患者不存在）
     */
    @Operation(summary = "住院证登记（建单入候床队列）", operationId = "createAdmission")
    @PostMapping("/api/v1/inpatient/admissions")
    @AuditLog(actionType = AuditActionType.WRITE)
    public AdmissionVO create(@Valid @RequestBody AdmissionCreateRequest req) {
        return admissionService.create(req);
    }

    /**
     * 候床队列分页查询（排序权重=急诊优先＞预约时段＞候床时长）。
     *
     * @param status 状态过滤（可空=全部状态；候床队列视图常规传 WAITING/SCHEDULED），可空；来源：查询参数
     * @param page   页码（0 基），缺省 0
     * @param size   单页条数（1-200），缺省 20
     * @return 住院证分页出参
     */
    @Operation(summary = "候床队列分页（急诊优先>预约时段>候床时长）", operationId = "listAdmissions")
    @GetMapping("/api/v1/inpatient/admissions")
    public PageResult<AdmissionVO> queue(
            @RequestParam(value = "status", required = false) AdmissionStatus status,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return admissionService.queue(status, page, size);
    }

    /**
     * 预约入院/预住院（WAITING→SCHEDULED；携目标床位时同事务联动床位预占）。
     *
     * @param no  住院证号（路径参数）
     * @param req 预约入参（目标病区/床位、预约日期），非空；来源：登记台签床调度
     * @return 预约后出参（status=SCHEDULED）
     * @throws com.fuyun.common.exception.BizException IP-1001（404 住院证不存在）/
     *                 IP-1002（409 非 WAITING 态禁止预约）
     */
    @Operation(summary = "预约入院/预住院（WAITING→SCHEDULED）", operationId = "scheduleAdmission")
    @PostMapping("/api/v1/inpatient/admissions/{no}/schedule")
    @AuditLog(actionType = AuditActionType.WRITE)
    public AdmissionVO schedule(@PathVariable("no") String no, @Valid @RequestBody AdmissionScheduleRequest req) {
        return admissionService.schedule(no, req);
    }

    /**
     * 住院证作废（WAITING/SCHEDULED→CANCELLED 终态；宽容联动释放——回读床行实态仅 RESERVED
     * 才释放，非预占态 warn 留痕放行作废）。
     *
     * @param no 住院证号（路径参数）
     * @return 作废后出参（status=CANCELLED）
     * @throws com.fuyun.common.exception.BizException IP-1001（404 住院证不存在）/
     *                 IP-1002（409 终态证禁止作废）
     */
    @Operation(summary = "住院证作废（候床/预约态→CANCELLED）", operationId = "cancelAdmission")
    @PostMapping("/api/v1/inpatient/admissions/{no}/cancel")
    @AuditLog(actionType = AuditActionType.WRITE)
    public AdmissionVO cancel(@PathVariable("no") String no) {
        return admissionService.cancel(no);
    }

    /**
     * 入院登记确认（<b>红线方法</b>：单事务=admission→COMPLETED + 签发 I 型 14 位 visit_id +
     * inpatient_visit 落 REGISTERED + 发布 inpatient.visit.registered）。
     *
     * @param no  住院证号（路径参数）
     * @param req 登记入参（医保类型），非空；来源：登记台核验医保凭证
     * @return 就诊出参（status=REGISTERED，visitId 已签发）
     * @throws com.fuyun.common.exception.BizException IP-1001/IP-1003/IP-1002/IP-1023（服务接口注全清单）
     */
    @Operation(summary = "入院登记确认（同事务签发 I 型 visit_id）", operationId = "registerAdmission")
    @PostMapping("/api/v1/inpatient/admissions/{no}/register")
    @AuditLog(actionType = AuditActionType.WRITE)
    public InpatientVisitVO register(@PathVariable("no") String no, @Valid @RequestBody VisitRegisterRequest req) {
        return admissionService.register(no, req);
    }

    /**
     * 入科确认（visit REGISTERED→ADMITTED；同事务联动床位 RESERVED→OCCUPIED 与 bed_assign 开账）。
     *
     * @param visitId 住院就诊号（I 型 14 位，路径参数）
     * @param req     入科入参（病区/床位/护理级别），非空；来源：病区护士站入科单
     * @return 入科后就诊出参（status=ADMITTED）
     * @throws com.fuyun.common.exception.BizException IP-1007/IP-1008/IP-1022/IP-1023（服务接口注全清单）
     */
    @Operation(summary = "入科确认（REGISTERED→ADMITTED）", operationId = "admitWard")
    @PostMapping("/api/v1/inpatient/visits/{visitId}/admit-ward")
    @AuditLog(actionType = AuditActionType.WRITE)
    public InpatientVisitVO admitWard(
            @PathVariable("visitId") String visitId, @Valid @RequestBody WardAdmitRequest req) {
        return admissionService.admitWard(visitId, req);
    }

    /**
     * 病区欠费清单（FU-M04-08 住院计费入口，五大降级清单②「欠费提醒=工作站列表可见」）：
     * arrears_flag=true 的在院就诊聚合，患者摘要脱敏展示名出网（GC22 禁全名/身份证）。
     * 读端点不挂审计（queue/bedMap 查询面同款先例——@AuditLog 仅写操作）。
     *
     * @param wardId 病区编码，必填；来源：查询参数（护士站一览）
     * @return 欠费清单行（标识时点倒序；病区无欠费在院患者返回空清单）
     */
    @Operation(summary = "病区欠费清单（欠费标识在院聚合，患者摘要脱敏）", operationId = "listArrearsVisits")
    @GetMapping("/api/v1/inpatient/visits/arrears")
    public List<ArrearsAlarmVO> arrears(@RequestParam("wardId") @NotBlank(message = "wardId 不能为空") String wardId) {
        return admissionService.arrearsList(wardId);
    }
}
