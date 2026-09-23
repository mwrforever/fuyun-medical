package com.fuyun.nursing.controller;

import com.fuyun.nursing.dto.PdaPatrolRequest;
import com.fuyun.nursing.service.IPdaService;
import com.fuyun.nursing.vo.NursingTaskVO;
import com.fuyun.nursing.vo.PdaPatientSummaryVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PDA 护理面端点（/api/v1/nursing/pda，Task 10）：床旁双入口——标识解析患者摘要
 * （敏感查询留痕 SENSITIVE_QUERY）与巡视打卡（写留痕 WRITE）。摄像头扫码归 P2
 * （P1 手工录入模拟）；无归属病区患者查询的 WARD 隔离随 M01 数据范围拦截器（P1-later）。
 * 类级 @RequestMapping 不承载（NursingTaskController 同款：端点集合可结构断言）。
 */
@Tag(name = "PDA 护理面")
@RestController
@RequiredArgsConstructor
public class PdaController {

    private final IPdaService pdaService;

    /**
     * PDA 患者摘要（标识三合一解析：腕带就诊编码/就诊卡号/证件号；脱敏输出，
     * 不在区降级为基本信息）。
     *
     * @param identifier 扫码标识，必填（PDA 扫码或手工录入）
     * @return 患者摘要出参（姓名掩码，证件号/手机号类字段不返回）
     */
    @Operation(summary = "PDA 患者摘要（标识三合一解析，脱敏输出）")
    @GetMapping("/api/v1/nursing/pda/patient-summary")
    @AuditLog(actionType = AuditActionType.SENSITIVE_QUERY)
    public PdaPatientSummaryVO patientSummary(@RequestParam("identifier") String identifier) {
        return pdaService.patientSummary(identifier);
    }

    /**
     * PDA 巡视打卡（扫码建 PATROL 行直落 COMPLETED；标识与就诊号归属双因子校验）。
     *
     * @param req 打卡入参（identifier 扫码标识 + visitId 归属校验键），非空
     * @return 打卡任务出参（COMPLETED 态，携 taskNo）
     */
    @Operation(summary = "PDA 巡视打卡（扫码直落 COMPLETED）")
    @PostMapping("/api/v1/nursing/pda/patrol")
    @AuditLog(actionType = AuditActionType.WRITE)
    public NursingTaskVO patrol(@Valid @RequestBody PdaPatrolRequest req) {
        return pdaService.patrol(req);
    }
}
