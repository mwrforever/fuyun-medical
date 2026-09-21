package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.context.RoleContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.CareRelationQuery;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.convert.PatientConverter;
import com.fuyun.patient.dto.UnmaskRequest;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.entity.PrivacyAccessLog;
import com.fuyun.patient.enums.MaskTargetField;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.mapper.PrivacyAccessLogMapper;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.service.PrivacyMaskService;
import com.fuyun.patient.service.PrivacyService;
import com.fuyun.patient.vo.PrivacyAccessLogVO;
import com.fuyun.patient.vo.UnmaskVO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 隐私明文查阅与留痕实现（FU-M02-06）：unmask 为全仓唯一明文出口——角色豁免 + 诊疗关系
 * D-16 三态双道校验 403 前置（不落台账、不返明文），解密取值仅在本方法生命周期与响应体内
 * 存活；成功由 @AuditLog SENSITIVE_QUERY 审计行 + privacy_access_log 台账行双留痕，失败由
 * 审计 FAIL 行留痕。豁免判定复用 PrivacyMaskService isExempt（禁复制判定逻辑，A.4.3-21）。
 *
 * <p>诊疗关系第二道（{@link CareRelationQuery} SPI，D-16 冻结语义）：容器无实现时跳过维持
 * 角色豁免单门禁现状（ObjectProvider 空安全，不 NPE）；任一实现（M03 门诊在途诊疗关系）
 * 注册后自动收紧为「无豁免且无诊疗关系即 403」。档案导出/全景调阅 access_type 词表预留
 * 不实现（无调用方）。
 */
@Slf4j
public class PrivacyServiceImpl implements PrivacyService {

    /** 查阅类型词表：明文查阅（ARCHIVE_EXPORT/PANORAMA_VIEW 预留不实现，V103 列注释口径） */
    private static final String ACCESS_TYPE_UNMASK_QUERY = "UNMASK_QUERY";

    /** traceId 的 MDC 键：与 TraceIdFilter/GlobalExceptionHandler 默认键一致 */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final PrivacyMaskService privacyMaskService;

    private final IPatientService patientService;

    private final PrivacyAccessLogMapper privacyAccessLogMapper;

    private final PatientFieldCrypto crypto;

    /** 诊疗关系查询 SPI（第二道门禁依据）：容器无实现为 null（冻结语义=维持角色豁免单门禁） */
    private final CareRelationQuery careRelationQuery;

    /**
     * 全参构造器（装配归 PatientWebConfig @Import）。
     *
     * @param privacyMaskService      脱敏引擎（豁免判定复用，禁复制逻辑），非空
     * @param patientService          患者主表服务（解密载体：读 patient 行取 cipher 列），非空
     * @param privacyAccessLogMapper  查阅台账只增表 mapper（落痕与分页检索），非空
     * @param crypto                  敏感字段加密构件（AES-GCM 解密），非空
     * @param careRelationProvider    诊疗关系 SPI 探针（ObjectProvider 空安全取用），非空；
     *                                容器无实现时解析值为 null（维持单门禁，M03 注册后自动收紧）
     */
    public PrivacyServiceImpl(
            PrivacyMaskService privacyMaskService,
            IPatientService patientService,
            PrivacyAccessLogMapper privacyAccessLogMapper,
            PatientFieldCrypto crypto,
            ObjectProvider<CareRelationQuery> careRelationProvider) {
        this.privacyMaskService = privacyMaskService;
        this.patientService = patientService;
        this.privacyAccessLogMapper = privacyAccessLogMapper;
        this.crypto = crypto;
        this.careRelationQuery = careRelationProvider.getIfAvailable();
    }

    /**
     * 明文查阅（双留痕出口）。
     *
     * @param request 查阅请求，非空
     * @return 明文值集，非空
     * @throws BizException PAT-1018/PAT-1001
     */
    @Override
    @Transactional
    public UnmaskVO unmask(UnmaskRequest request) {
        // ①角色豁免判定：全字段豁免=角色单门禁直接放行；存在非豁免字段时进入 ② 诊疗关系第二道
        List<String> roles = RoleContextHolder.get();
        boolean exemptAll = true;
        String firstUnexemptField = null;
        for (String field : request.fields()) {
            if (!privacyMaskService.isExempt(roles, field)) {
                exemptAll = false;
                firstUnexemptField = field;
                break;
            }
        }
        String operatorId = OperatorContextHolder.get() == null ? "system" : OperatorContextHolder.get();
        // ②诊疗关系校验（D-16 三态第二道）：SPI 无实现=跳过维持单门禁（warn，冻结语义）；
        //   有实现时非豁免字段须命中在途诊疗关系，否则 403（不落查阅台账，留痕由审计切面 FAIL 行承担）
        if (!exemptAll
                && careRelationQuery != null
                && !careRelationQuery.hasCareRelation(request.patientId(), operatorId)) {
            log.warn("明文查阅拒绝（无豁免角色且无在途诊疗关系）：patientId={}", request.patientId());
            throw new BizException(PatientErrorCode.UNMASK_NOT_AUTHORIZED, HttpStatus.FORBIDDEN, "无豁免角色且无在途诊疗关系");
        }
        if (!exemptAll && careRelationQuery == null) {
            log.warn("明文查阅拒绝（无豁免角色）：patientId={}，field={}", request.patientId(), firstUnexemptField);
            throw new BizException(PatientErrorCode.UNMASK_NOT_AUTHORIZED, HttpStatus.FORBIDDEN, "无明文查阅权限");
        }
        // ③解密取值（明文仅在本方法生命周期与响应体内存活）
        Patient patient = patientService.getById(request.patientId());
        if (patient == null) {
            throw new BizException(PatientErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "患者档案不存在");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : request.fields()) {
            values.put(field, plaintextOf(patient, field));
        }
        // ④查阅台账落痕（只增表：谁看了谁的什么 + purpose + traceId，等保审计锚点）
        PrivacyAccessLog logRow = new PrivacyAccessLog();
        logRow.setOperatorId(operatorId);
        logRow.setPatientId(request.patientId());
        logRow.setAccessType(ACCESS_TYPE_UNMASK_QUERY);
        logRow.setPurpose(request.purpose());
        logRow.setFields(String.join(",", request.fields()));
        logRow.setTraceId(MDC.get(TRACE_ID_MDC_KEY));
        privacyAccessLogMapper.insert(logRow);
        // 成功日志仅落元数据（字段词/操作人），禁打印 unmask 明文结果（敏感数据红线）
        log.info(
                "明文查阅留痕：patientId={}，fields={}，operator={}",
                request.patientId(),
                logRow.getFields(),
                logRow.getOperatorId());
        return new UnmaskVO(request.patientId(), values);
    }

    /**
     * 按字段词解密取值（name 为明文列直读，其余经构件解密；审查 I3：经 {@link MaskTargetField#ofColumn}
     * 收口后对枚举穷举分派、不写 default——非法词在 DTO @Pattern（400）与豁免判定（PAT-1018）两道闸后
     * 不可达，ofColumn 对未知词的抛错行由 PrivacyServiceImplTest 公开用例覆盖，满足行覆盖门禁）。
     */
    private String plaintextOf(Patient patient, String field) {
        MaskTargetField target = MaskTargetField.ofColumn(field);
        return switch (target) {
            case NAME -> patient.getName();
            case ID_CARD_NO -> crypto.decrypt(patient.getIdCardNoCipher());
            case MOBILE -> crypto.decrypt(patient.getMobileCipher());
            case ADDRESS -> crypto.decrypt(patient.getAddressCipher());
            case BIRTH_DATE ->
                patient.getBirthDate() == null ? null : patient.getBirthDate().toString();
        };
    }

    /**
     * 查阅台账分页。
     *
     * @param patientId 患者过滤，可空
     * @param page      0 基页码
     * @param size      1-200
     * @return 台账分页，非空
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<PrivacyAccessLogVO> listAccessLogs(Long patientId, int page, int size) {
        // MP Page 为 1 基：0 基契约 +1 换算（PageResult 出参仍以 0 基回显）
        Page<PrivacyAccessLog> result = new Page<>(page + 1, size);
        privacyAccessLogMapper.selectPage(
                result,
                new LambdaQueryWrapper<PrivacyAccessLog>()
                        .eq(patientId != null, PrivacyAccessLog::getPatientId, patientId)
                        .orderByDesc(PrivacyAccessLog::getOccurredAt));
        List<PrivacyAccessLogVO> rows = result.getRecords().stream()
                .map(row -> Mappers.getMapper(PatientConverter.class).toVO(row))
                .toList();
        return PageResult.of(rows, page, size, result.getTotal());
    }
}
