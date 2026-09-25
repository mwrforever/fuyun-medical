package com.fuyun.inpatient.controller;

import com.fuyun.inpatient.dto.BedAssignRequest;
import com.fuyun.inpatient.dto.BedReserveRequest;
import com.fuyun.inpatient.service.BedService;
import com.fuyun.inpatient.vo.BedMapVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * 床位管理端点（/api/v1/inpatient/beds）——FU-M04-02：床位图聚合与五态操作六端点
 * （04-inpatient Spec §6/§7）。controller 禁业务逻辑与事务（A.1-8）：五态状态机 CAS、
 * 占用流水开账与事件广播全归服务层；写端点挂 WRITE 审计（@AuditLog 注解 + AuditLogAspect
 * 上下文内拦截落 system.audit_log）。类级 @RequestMapping 不承载（方法级全路径自文档）。
 */
@Tag(name = "M04 床位管理", description = "床位图/预占/占床/释放/消毒完成/维修/维修恢复")
@RestController
@RequiredArgsConstructor
@Validated
public class BedController {

    private final BedService bedService;

    /**
     * 病区床位图（五态色标 + 包床标记 + 性别限制 + 占用 visit 摘要）。
     *
     * @param wardId 病区编码，必填；来源：查询参数（护士站一览/大屏）
     * @return 床位图行清单（床号升序；病区无床返回空清单）
     */
    @Operation(summary = "病区床位图聚合（五态+包床+占用摘要）", operationId = "getBedMap")
    @GetMapping("/api/v1/inpatient/beds/map")
    public List<BedMapVO> bedMap(@RequestParam("wardId") @NotBlank(message = "wardId 不能为空") String wardId) {
        return bedService.bedMap(wardId);
    }

    /**
     * 床位预占（FREE→RESERVED）：登记台签床/转科预占/全院一张床共用（预占不绑定就诊主体）。
     *
     * @param id  床位 id（路径参数）
     * @param req 预占入参（无业务字段——契约形状固化；预占主体由操作者上下文承载）
     * @throws com.fuyun.common.exception.BizException IP-1004/IP-1005/IP-1006（服务接口注全清单）
     */
    @Operation(summary = "床位预占（FREE→RESERVED）", operationId = "reserveBed")
    @PostMapping("/api/v1/inpatient/beds/{id}/reserve")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void reserve(@PathVariable("id") Long id, @Valid @RequestBody BedReserveRequest req) {
        bedService.reserve(id);
    }

    /**
     * 床位占床（FREE/RESERVED→OCCUPIED，直接分配快速通道）：开占用流水并广播 bed.changed。
     *
     * @param id  床位 id（路径参数）
     * @param req 占床入参（占用主体就诊号），非空；来源：护士站分配床位
     * @throws com.fuyun.common.exception.BizException IP-1004/IP-1005/IP-1006/IP-1007/IP-1008（服务接口注全清单）
     */
    @Operation(summary = "床位占床（FREE/RESERVED→OCCUPIED 开流水）", operationId = "assignBed")
    @PostMapping("/api/v1/inpatient/beds/{id}/assign")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void assign(@PathVariable("id") Long id, @Valid @RequestBody BedAssignRequest req) {
        bedService.assign(id, req);
    }

    /**
     * 释放预占（RESERVED→FREE）：住院证作废联动同源路径；占用态须走转床/转科/出院编排。
     *
     * @param id 床位 id（路径参数）
     * @throws com.fuyun.common.exception.BizException IP-1004/IP-1005（服务接口注全清单）
     */
    @Operation(summary = "释放床位预占（RESERVED→FREE）", operationId = "releaseBed")
    @PostMapping("/api/v1/inpatient/beds/{id}/release")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void release(@PathVariable("id") Long id) {
        bedService.release(id);
    }

    /**
     * 消毒完成确认（DISINFECTING→FREE）：终末消毒完成回可分配池。
     *
     * @param id 床位 id（路径参数）
     * @throws com.fuyun.common.exception.BizException IP-1004/IP-1005（服务接口注全清单）
     */
    @Operation(summary = "消毒完成确认（DISINFECTING→FREE）", operationId = "disinfectDone")
    @PostMapping("/api/v1/inpatient/beds/{id}/disinfect-done")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void disinfectDone(@PathVariable("id") Long id) {
        bedService.disinfectDone(id);
    }

    /**
     * 床位转维修（FREE→MAINTENANCE）。
     *
     * @param id 床位 id（路径参数）
     * @throws com.fuyun.common.exception.BizException IP-1004/IP-1005（服务接口注全清单）
     */
    @Operation(summary = "床位转维修（FREE→MAINTENANCE）", operationId = "maintainBed")
    @PostMapping("/api/v1/inpatient/beds/{id}/maintain")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void maintain(@PathVariable("id") Long id) {
        bedService.maintain(id);
    }

    /**
     * 维修恢复（MAINTENANCE→FREE）。
     *
     * @param id 床位 id（路径参数）
     * @throws com.fuyun.common.exception.BizException IP-1004/IP-1005（服务接口注全清单）
     */
    @Operation(summary = "维修恢复（MAINTENANCE→FREE）", operationId = "maintainDone")
    @PostMapping("/api/v1/inpatient/beds/{id}/maintain-done")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void maintainDone(@PathVariable("id") Long id) {
        bedService.maintainDone(id);
    }
}
