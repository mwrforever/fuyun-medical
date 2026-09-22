package com.fuyun.nursing.controller;

import com.fuyun.nursing.dto.NurseAssignmentRequest;
import com.fuyun.nursing.dto.WardPatientRegisterRequest;
import com.fuyun.nursing.dto.WardPatientRemoveRequest;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.NurseAssignmentVO;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import com.fuyun.nursing.vo.WardPatientVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 病区元数据端点（/api/v1/nursing/ward-patients + /api/v1/nursing/assignments）。
 * <b>端点面冻结</b>（2026-09-22 批复「禁写路径下渗」的可执行锚，WardMetaServiceImplTest
 * noAdtWriteEndpointExposed 结构断言钉死）：ward 面仅四端点 + 责任分配三端点，公开端点集合
 * 逐字等于冻结清单；任何新增/扩展（含独立 PUT/PATCH 视图属性变更端点）须先回决策点重新上报
 * ——视图属性变更 P1 期间只能经 register 幂等 upsert 承载，「移出 + 重新登记」模拟变更，
 * 禁为便利恢复独立写端点。wardConfig 为服务面能力（Task 5/9 模块内消费），不在公开端点清单。
 * 类级 @RequestMapping 不承载（端点集合结构断言需方法级全路径，DispenseController 同款）。
 */
@Tag(name = "病区元数据")
@RestController
@RequiredArgsConstructor
public class WardController {

    private final IWardMetaService wardMetaService;

    /**
     * 入区登记（P1 过渡通道，幂等 upsert；Mermaid 结构断言锚端点）。
     *
     * @param req 登记入参，非空
     * @return 登记行出参
     */
    @Operation(summary = "入区登记（P1 过渡通道，幂等 upsert）")
    @PostMapping("/api/v1/nursing/ward-patients")
    @AuditLog(actionType = AuditActionType.WRITE)
    public WardPatientVO register(@Valid @RequestBody WardPatientRegisterRequest req) {
        return wardMetaService.register(req);
    }

    /**
     * 移出病区一览（GC38 四护栏：仅本地视图行置 REMOVED，零外发、无住院业务状态变更）。
     *
     * @param visitId 住院就诊号（路径参数）
     * @param req     移出入参（reason 留痕），非空
     * @return 确认出参（仅 visitId 有值）
     */
    @Operation(summary = "移出病区一览")
    @PostMapping("/api/v1/nursing/ward-patients/{visitId}/remove")
    @AuditLog(actionType = AuditActionType.WRITE)
    public WardPatientVO remove(
            @PathVariable("visitId") String visitId, @Valid @RequestBody WardPatientRemoveRequest req) {
        return wardMetaService.remove(visitId, req);
    }

    /**
     * 病区在区患者一览（床位序）。
     *
     * @param wardId 病区编码，必填
     * @return 在区行出参清单（床位序）
     */
    @Operation(summary = "病区在区患者一览")
    @GetMapping("/api/v1/nursing/ward-patients")
    public List<WardPatientVO> listByWard(@RequestParam("wardId") String wardId) {
        return wardMetaService.listByWard(wardId);
    }

    /**
     * 患者详情卡聚合（不含体征摘要——前端另调体征查询组装）。
     *
     * @param visitId 住院就诊号（路径参数）
     * @return 详情卡出参
     */
    @Operation(summary = "患者详情卡")
    @GetMapping("/api/v1/nursing/ward-patients/{visitId}")
    public WardPatientDetailVO detail(@PathVariable("visitId") String visitId) {
        return wardMetaService.detail(visitId);
    }

    /**
     * 当班责任护士分配清单。
     *
     * @param wardId    病区编码，必填
     * @param shiftCode 班次 code，必填
     * @return 分配出参清单
     */
    @Operation(summary = "当班责任护士分配清单")
    @GetMapping("/api/v1/nursing/assignments")
    public List<NurseAssignmentVO> listAssignments(
            @RequestParam("wardId") String wardId, @RequestParam("shiftCode") String shiftCode) {
        return wardMetaService.listAssignments(wardId, shiftCode);
    }

    /**
     * 新增责任护士分配（FU-M05-01）。
     *
     * @param req 分配入参，非空
     * @return 分配行出参
     */
    @Operation(summary = "新增责任护士分配")
    @PostMapping("/api/v1/nursing/assignments")
    @AuditLog(actionType = AuditActionType.WRITE)
    public NurseAssignmentVO assign(@Valid @RequestBody NurseAssignmentRequest req) {
        return wardMetaService.assign(req);
    }

    /**
     * 撤销责任分配（ACTIVE→CANCELLED 留痕）。
     *
     * @param id 分配 id（路径参数）
     */
    @Operation(summary = "撤销责任分配")
    @DeleteMapping("/api/v1/nursing/assignments/{id}")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void unassign(@PathVariable("id") long id) {
        wardMetaService.unassign(id);
    }
}
