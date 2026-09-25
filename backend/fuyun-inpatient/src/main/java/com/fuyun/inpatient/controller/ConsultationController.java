package com.fuyun.inpatient.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.dto.ConsultationCreateRequest;
import com.fuyun.inpatient.dto.ConsultationOpinionRequest;
import com.fuyun.inpatient.enums.ConsultationStatus;
import com.fuyun.inpatient.service.ConsultationService;
import com.fuyun.inpatient.vo.ConsultationVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会诊管理端点（/api/v1/inpatient/consultations）——FU-M04-09 五端点（04-inpatient Spec §7）：
 * 会诊申请（响应时限按紧急程度服务端计算：急会诊 +30min / 普通 +24h）/受邀科接单（逾期单
 * 仍可接单清标记）/会诊意见提交（ACCEPTED→COMPLETED 归档供 M09 引用）/取消/分页列表
 * （读时惰性逾期判定承载面——REQUESTED 越限行置 overdue_flag 并发布 overdue 动作事件一次）。
 * controller 禁业务逻辑与事务（A.1-8）：守卫链/CAS 状态机/逾期升级/事件发布全归服务层；
 * 写端点挂 WRITE 审计（@AuditLog + AuditLogAspect 落 system.audit_log——GC22），列表读端点
 * 不挂审计（AdmissionController 先例）。类级 @RequestMapping 不承载（方法级全路径自文档）。
 */
@Tag(name = "M04 会诊管理", description = "会诊申请/受邀科接单/意见提交/取消/会诊列表（读时惰性逾期升级）")
@RestController
@RequiredArgsConstructor
@Validated
public class ConsultationController {

    private final ConsultationService consultationService;

    /**
     * 会诊申请（独立申请路径；CONSULT 类医嘱审核钩子自动建草稿不经本端点）。
     *
     * @param req 申请入参（就诊号/受邀科室/紧急程度/级别/原因），非空；来源：医生站会诊申请单
     * @return 申请后出参（status=REQUESTED，响应截止已按时限计算），非空
     * @throws com.fuyun.common.exception.BizException IP-1007/IP-1008/IP-1022/IP-1023
     *                 （服务接口注全清单）
     */
    @Operation(summary = "会诊申请（急会诊 30min/普通 24h 响应时限）", operationId = "createConsultation")
    @PostMapping("/api/v1/inpatient/consultations")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ConsultationVO create(@Valid @RequestBody ConsultationCreateRequest req) {
        return consultationService.create(req);
    }

    /**
     * 受邀科接单（REQUESTED→ACCEPTED；逾期升级过的会诊单仍可接单——接单清 overdue_flag，
     * 超时为动作非状态迁移）。
     *
     * @param no 会诊单号（路径参数），非空
     * @return 接单后出参（status=ACCEPTED），非空
     * @throws com.fuyun.common.exception.BizException IP-1019/IP-1020/IP-1022
     */
    @Operation(summary = "受邀科接单（逾期单仍可接单）", operationId = "acceptConsultation")
    @PostMapping("/api/v1/inpatient/consultations/{no}/accept")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ConsultationVO accept(@PathVariable("no") String no) {
        return consultationService.accept(no);
    }

    /**
     * 会诊意见提交（ACCEPTED→COMPLETED；意见归档供 M09 病历引用）。
     *
     * @param no  会诊单号（路径参数），非空
     * @param req 意见入参（意见文本），非空；来源：受邀科医生会诊意见单
     * @return 完成后出参（status=COMPLETED），非空
     * @throws com.fuyun.common.exception.BizException IP-1019/IP-1020/IP-1022
     */
    @Operation(summary = "会诊意见提交（完成闭环归档）", operationId = "submitConsultationOpinion")
    @PostMapping("/api/v1/inpatient/consultations/{no}/opinion")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ConsultationVO opinion(@PathVariable("no") String no, @Valid @RequestBody ConsultationOpinionRequest req) {
        return consultationService.opinion(no, req);
    }

    /**
     * 取消会诊（REQUESTED/ACCEPTED→CANCELLED；COMPLETED 终态已归档意见不可取消）。
     *
     * @param no 会诊单号（路径参数），非空
     * @return 取消后出参（status=CANCELLED），非空
     * @throws com.fuyun.common.exception.BizException IP-1019/IP-1020/IP-1022
     */
    @Operation(summary = "取消会诊", operationId = "cancelConsultation")
    @PostMapping("/api/v1/inpatient/consultations/{no}/cancel")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ConsultationVO cancel(@PathVariable("no") String no) {
        return consultationService.cancel(no);
    }

    /**
     * 会诊单分页查询（读时惰性逾期承载面：当前页 REQUESTED 越限未标记行置 overdue_flag 并
     * 发布 overdue 动作事件一次——状态停留 REQUESTED 仍可接单；升级动作降级为列表标记可见，
     * 通知中心 P3）。
     *
     * @param status 状态过滤（REQUESTED/ACCEPTED/COMPLETED/CANCELLED；可空=全部状态），可空；
     *               来源：查询参数
     * @param deptId 科室编码过滤（申请/受邀任一侧命中；可空=不过滤），可空；来源：查询参数
     * @param page   页码（0 基），缺省 0
     * @param size   单页条数（1-200），缺省 20
     * @return 会诊分页出参（行内 overdueFlag 为置位后实态）
     */
    @Operation(summary = "会诊单分页（读时惰性逾期升级）", operationId = "listConsultations")
    @GetMapping("/api/v1/inpatient/consultations")
    public PageResult<ConsultationVO> list(
            @RequestParam(value = "status", required = false) ConsultationStatus status,
            @RequestParam(value = "deptId", required = false) String deptId,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return consultationService.list(status, deptId, page, size);
    }
}
