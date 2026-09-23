package com.fuyun.nursing.controller;

import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.dto.NursingAssessmentCreateRequest;
import com.fuyun.nursing.enums.ScaleType;
import com.fuyun.nursing.service.INursingAssessmentService;
import com.fuyun.nursing.vo.NursingAssessmentVO;
import com.fuyun.nursing.vo.ScaleDefinitionVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 护理评估端点（/api/v1/nursing/assessment-scales + /api/v1/nursing/assessments，GC13 资源
 * 复数 + 小写连字符）。三端点：量表定义清单（前端渲染评估表单唯一数据源）、评估单创建
 * （判级 + 高危联动）、患者评估清单（scaleType 可选过滤）。类级 @RequestMapping 不承载
 * （WardController 同款：端点集合可结构断言）。
 */
@Tag(name = "护理评估")
@RestController
@RequiredArgsConstructor
public class NursingAssessmentController {

    private final INursingAssessmentService assessmentService;

    /**
     * 评估量表定义清单（五量表冻结定义：条目词表/取值域/总分规则）。
     *
     * @return 量表定义出参清单（BRADEN→MORSE→NRS→BARTHEL→MEWS 冻结展示序）
     */
    @Operation(summary = "评估量表定义清单（评估表单渲染唯一数据源）")
    @GetMapping("/api/v1/nursing/assessment-scales")
    public List<ScaleDefinitionVO> scales() {
        return assessmentService.scales();
    }

    /**
     * 护理评估单创建（量表引擎判级；高危自动生成防范任务并回写床旁风险标识，发布评估完成事件）。
     *
     * @param req 创建入参，非空
     * @return 评估单出参（含判级结果与复评计划）
     */
    @Operation(summary = "护理评估单创建（五量表判级 + 高危联动）")
    @PostMapping("/api/v1/nursing/assessments")
    @AuditLog(actionType = AuditActionType.WRITE)
    public NursingAssessmentVO create(@Valid @RequestBody NursingAssessmentCreateRequest req) {
        return assessmentService.create(req);
    }

    /**
     * 患者评估单清单（评估时点降序，最新评估优先）。
     *
     * @param visitId   住院就诊号，必填
     * @param scaleType 量表类型 code（ScaleType 词表，可空=全量表，非法值 NS-1019）
     * @return 评估单出参清单（按评估时点降序）
     */
    @Operation(summary = "患者评估单清单")
    @GetMapping("/api/v1/nursing/assessments")
    public List<NursingAssessmentVO> listByVisit(
            @RequestParam("visitId") String visitId,
            @RequestParam(value = "scaleType", required = false) String scaleType) {
        // 量表类型 code 显式格式校验（W-22⑦ 禁裸转换——Spring 枚举直绑词表外值会被吞成通用 400，此处显式 NS-1019）
        ScaleType scaleTypeEnum = scaleType == null ? null : ScaleType.fromCode(scaleType);
        if (scaleType != null && scaleTypeEnum == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "量表类型 code 非法：" + scaleType);
        }
        return assessmentService.listByVisit(visitId, scaleTypeEnum);
    }
}
