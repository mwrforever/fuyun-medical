package com.fuyun.nursing.controller;

import com.fuyun.nursing.dto.NurseAssignmentRequest;
import com.fuyun.nursing.service.IWardAccessService;
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
 * 病区元数据读面端点（/api/v1/nursing/ward-patients 两 GET + /api/v1/nursing/assignments 三端点）。
 * <b>W-34 退役声明（2026-10）</b>：P1 过渡通道 POST /ward-patients（入区登记）与
 * POST /ward-patients/{visitId}/remove（移出病区一览）两端点连同 DTO/枚举/服务面整体退役——
 * nursing_ward_patient 自此为<b>纯事件投影</b>，单一写入面=InpatientVisitEventListener 四路消费
 * （inpatient.visit.admitted upsert / visit.transferred 归属更新 / visit.discharged 逻辑删 /
 * bed.changed 补床号），本控制器只余读面（一览/详情卡）与责任分配。端点面冻结（2026-09-22 批复
 * 「禁写路径下渗」的可执行锚，WardMetaServiceImplTest.noAdtWriteEndpointExposed 结构断言钉死）：
 * 公开端点集合逐字等于冻结清单；任何新增/扩展须先回决策点重新上报（禁为便利恢复任何 ADT 写
 * 端点——住院业务状态零权威红线）。wardConfig 为服务面能力（模块内消费），不在公开端点清单。
 * 类级 @RequestMapping 不承载（端点集合结构断言需方法级全路径，DispenseController 同款）。
 */
@Tag(name = "病区元数据")
@RestController
@RequiredArgsConstructor
public class WardController {

    private final IWardMetaService wardMetaService;

    private final IWardAccessService wardAccessService;

    /**
     * 病区在区患者一览（床位序）。
     * W-40：请求病区须 ∈ 操作者当班绑定集（fail-closed，NS-1028）。
     *
     * @param wardId 病区编码，必填
     * @return 在区行出参清单（床位序）
     * @throws com.fuyun.common.exception.BizException NS-1028（403 病区不在当班绑定集或无有效绑定）
     */
    @Operation(summary = "病区在区患者一览")
    @GetMapping("/api/v1/nursing/ward-patients")
    public List<WardPatientVO> listByWard(@RequestParam("wardId") String wardId) {
        // W-40：请求病区须 ∈ 操作者当班绑定集（fail-closed，NS-1028）
        wardAccessService.assertWardAllowed(wardId);
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
