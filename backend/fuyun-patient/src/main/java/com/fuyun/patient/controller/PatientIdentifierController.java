package com.fuyun.patient.controller;

import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.dto.IdentifierCreateRequest;
import com.fuyun.patient.dto.ResolveRequest;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.service.IPatientIdentifierService;
import com.fuyun.patient.vo.IdentifierVO;
import com.fuyun.patient.vo.ResolveVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import java.util.List;
import org.mapstruct.factory.Mappers;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标识与解析端点（M02 Spec §7：POST /identifiers/resolve 全院高频入口 + 补挂/清单两端点）。
 */
@RestController
@RequestMapping("/api/v1/patient")
public class PatientIdentifierController {

    private final IPatientIdentifierService identifierService;

    private final PatientContextResolver contextResolver;

    /** 构造器注入（A.1-7），装配归 PatientWebConfig @Import */
    public PatientIdentifierController(
            IPatientIdentifierService identifierService, PatientContextResolver contextResolver) {
        this.identifierService = identifierService;
        this.contextResolver = contextResolver;
    }

    /**
     * 标识解析（POST /identifiers/resolve）：标识→归一主档视图 + 拦截标记。
     *
     * @param request 解析请求（@Valid）；来源：业务模块/前端持卡场景
     * @return 归一视图；200
     */
    @PostMapping("/identifiers/resolve")
    public ResolveVO resolve(@Valid @RequestBody ResolveRequest request) {
        PatientIdentifier identifier =
                identifierService.resolveActive(request.identifierType(), request.identifierValue());
        PatientContextView view = contextResolver.resolve(identifier.getPatientId());
        return new ResolveVO(
                view.patientId(), view.resolvedPatientId(), view.status(), view.blocked(), view.blockReason());
    }

    /**
     * 补挂标识（POST /patients/{patientId}/identifiers；WRITE 审计；identifier.changed 事件）。
     *
     * @param patientId 患者主索引
     * @param request   补挂请求（@Valid；卡类介质必须携卡面号）
     * @return 标识出参；201
     */
    @PostMapping("/patients/{patientId}/identifiers")
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public IdentifierVO attach(@PathVariable long patientId, @Valid @RequestBody IdentifierCreateRequest request) {
        boolean isCard =
                "VISIT_CARD".equals(request.identifierType()) || "HEALTH_CARD".equals(request.identifierType());
        if (isCard && (request.cardNo() == null || request.cardNo().isBlank())) {
            throw new BizException(PatientErrorCode.IDENTIFIER_ALREADY_BOUND, HttpStatus.BAD_REQUEST, "卡类介质必须提供卡面号");
        }
        Long id = identifierService.attach(
                patientId,
                request.identifierType(),
                request.identifierValue(),
                isCard ? request.cardNo() : null,
                false);
        identifierService.publishChanged(patientId, request.identifierType(), request.identifierValue(), "BOUND");
        PatientIdentifier row = identifierService.getById(id);
        return Mappers.getMapper(com.fuyun.patient.convert.PatientConverter.class)
                .toVO(row);
    }

    /**
     * 标识清单（GET /patients/{patientId}/identifiers；只出 cardNo 不出标识值）。
     *
     * @param patientId 患者主索引
     * @return 标识出参清单；200
     */
    @GetMapping("/patients/{patientId}/identifiers")
    public List<IdentifierVO> list(@PathVariable long patientId) {
        return identifierService.listByPatient(patientId).stream()
                .map(row -> Mappers.getMapper(com.fuyun.patient.convert.PatientConverter.class)
                        .toVO(row))
                .toList();
    }
}
