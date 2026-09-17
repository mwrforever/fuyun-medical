package com.fuyun.patient.service.impl;

import com.fuyun.patient.api.PatientCreatedPayload;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.dto.PatientCreateRequest;
import com.fuyun.patient.dto.PatientMatchCheckRequest;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.gateway.IdentityMediaGateway;
import com.fuyun.patient.internal.PatientDomainEvent;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.service.IPatientIdentifierService;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.service.IPossibleDuplicateService;
import com.fuyun.patient.service.IPrivacyAuthService;
import com.fuyun.patient.service.PatientMatchingService;
import com.fuyun.patient.service.PatientRegistrationService;
import com.fuyun.patient.vo.PatientMatchCheckVO;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/**
 * 患者建档实现（FU-M02-01/02 主流程时序 1）：
 * ①介质核验（统一适配器，手工兜底）→ ②匹配预检 → ③AUTO_MATCH 归一补挂标识（不新建）/
 *   新建档案（SUSPECT 同样新建+生成疑似重复，不自动合并）→ ④标识注册 → ⑤知情同意落痕 →
 *   ⑤.5 疑似重复待审行生成（SUSPECT 结论回填）→ ⑥patient.patient.created 应用事件（事务内发布，发布器 AFTER_COMMIT 出 MQ）。
 *
 * <p>敏感红线：日志只落 patientId 与结论词，证件号/手机号/住址明文禁入日志与事件载荷。
 */
@Slf4j
public class PatientRegistrationServiceImpl implements PatientRegistrationService {

    private final PatientMatchingService matchingService;

    private final IPatientService patientService;

    private final IPatientIdentifierService identifierService;

    private final IPrivacyAuthService privacyAuthService;

    private final IPossibleDuplicateService duplicateService;

    private final PatientFieldCrypto crypto;

    private final IdentityMediaGateway mediaGateway;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 全参构造器（装配归 PatientWebConfig @Import；ApplicationEventPublisher 由 Spring 容器供给）。
     *
     * @param matchingService     EMPI 匹配引擎（建档预检复用），非空
     * @param patientService      患者主表 IService（新建档案落库），非空
     * @param identifierService   标识注册表服务（介质挂接），非空
     * @param privacyAuthService  隐私授权服务（知情同意落痕），非空
     * @param duplicateService    疑似重复治理服务（SUSPECT 待审行生成），非空
     * @param crypto              加密构件（敏感列密文与盲索引），非空
     * @param mediaGateway        介质核验适配器（接口型 Bean，按类型注入），非空
     * @param eventPublisher      Spring 应用事件发布器（事务内发布），非空
     */
    public PatientRegistrationServiceImpl(
            PatientMatchingService matchingService,
            IPatientService patientService,
            IPatientIdentifierService identifierService,
            IPrivacyAuthService privacyAuthService,
            IPossibleDuplicateService duplicateService,
            PatientFieldCrypto crypto,
            IdentityMediaGateway mediaGateway,
            ApplicationEventPublisher eventPublisher) {
        this.matchingService = matchingService;
        this.patientService = patientService;
        this.identifierService = identifierService;
        this.privacyAuthService = privacyAuthService;
        this.duplicateService = duplicateService;
        this.crypto = crypto;
        this.mediaGateway = mediaGateway;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 建档主用例（写事务）。
     *
     * @param request 建档请求，非空；来源：controller @Valid 校验后
     * @return 建档结果载体，非空
     */
    @Override
    @Transactional
    public PatientMatchCheckVO register(PatientCreateRequest request) {
        // ①介质核验统一入口（手工兜底实现直通；读卡/健康卡/医保通道联调后替换实现）。
        // 取舍注明（审查 M10）：实名判据恒为证件号——仅录卡号而证件号为空的场景按未实名兜底
        // （卡号不作实名凭证，未实名标记必落红线优先）；如需「卡号即人工核实」属口径变更，另立决策。
        IdentityMediaGateway.IdentityExtract extract = mediaGateway.verify(
                request.identifierType() != null ? request.identifierType() : "ID_CARD", request.idCardNo());
        boolean realName = extract.verified();
        // ②匹配预检（复用引擎，只读）
        PatientMatchCheckVO check = matchingService.preCheck(new PatientMatchCheckRequest(
                request.name(), request.sex(), request.birthDate(), request.idCardNo(), request.mobile()));
        long patientId;
        if ("AUTO_MATCH".equals(check.outcome())) {
            // ③归一：返回既有 patient_id 并补挂本次标识，不新建（红线 1：patient_id 唯一权威）
            patientId = check.candidatePatientId();
            attachMediaIdentifier(patientId, request);
            log.info("建档归一既有主索引：patientId={}，outcome=AUTO_MATCH", patientId);
            return new PatientMatchCheckVO(check.outcome(), patientId, check.score(), check.matchedRules());
        }
        // 新建主索引（雪花 ASSIGN_ID 插入时生成）
        Patient patient = new Patient();
        patient.setName(request.name());
        patient.setNamePinyin(PinyinStub.toPinyin(request.name()));
        patient.setSex(request.sex());
        patient.setBirthDate(
                request.birthDate() == null || request.birthDate().isBlank()
                        ? null
                        : LocalDate.parse(request.birthDate()));
        patient.setEthnicity(request.ethnicity());
        patient.setMaritalStatus(request.maritalStatus());
        patient.setOccupation(request.occupation());
        patient.setBloodType(request.bloodType());
        patient.setIdCardNoCipher(crypto.encrypt(request.idCardNo()));
        patient.setIdCardNoHash(crypto.hash(request.idCardNo()));
        patient.setMobileCipher(crypto.encrypt(request.mobile()));
        patient.setMobileHash(crypto.hash(request.mobile()));
        patient.setAddressCipher(crypto.encrypt(request.address()));
        patient.setStatus("NORMAL");
        patient.setRealNameFlag(realName);
        patient.setRegisterChannel(request.registerChannel());
        patient.setArchiveSource(
                request.archiveSource() == null || request.archiveSource().isBlank()
                        ? "STANDARD"
                        : request.archiveSource());
        patientService.save(patient);
        patientId = patient.getPatientId();
        // ④标识注册（证件号同时作为 ID_CARD 标识登记，归一检索主路径）
        attachMediaIdentifier(patientId, request);
        // ⑤知情同意授权落痕（建档强制，FU-M02-01/06）
        privacyAuthService.recordInformedConsent(patientId, request.informedConsentRef());
        // ⑤.5 疑似重复生成（SUSPECT 且候选存在时；a<b 规范化 + 唯一兜底幂等，Task 8 回填点）
        if ("SUSPECT".equals(check.outcome()) && check.candidatePatientId() != null) {
            duplicateService.recordSuspect(patientId, check.candidatePatientId(), check);
        }
        // ⑥patient.patient.created 应用事件（事务内发布；发布器 AFTER_COMMIT 转 fy.topic）
        eventPublisher.publishEvent(new PatientDomainEvent(
                PatientMessagingConstants.EVENT_CREATED,
                new PatientCreatedPayload(
                        patientId,
                        request.sex(),
                        request.birthDate(),
                        realName,
                        request.registerChannel(),
                        patient.getArchiveSource())));
        log.info("建档完成：patientId={}，outcome={}，realNameFlag={}", patientId, check.outcome(), realName);
        return new PatientMatchCheckVO(check.outcome(), patientId, check.score(), check.matchedRules());
    }

    /** 挂接本次介质标识（证件号恒为 ID_CARD 强标识；其他介质按请求词表） */
    private void attachMediaIdentifier(long patientId, PatientCreateRequest request) {
        if (request.idCardNo() != null && !request.idCardNo().isBlank()) {
            identifierService.attach(patientId, "ID_CARD", request.idCardNo(), null, true);
        }
        if (request.identifierType() != null
                && request.identifierValue() != null
                && !request.identifierValue().isBlank()) {
            boolean isCard =
                    "VISIT_CARD".equals(request.identifierType()) || "HEALTH_CARD".equals(request.identifierType());
            identifierService.attach(
                    patientId,
                    request.identifierType(),
                    request.identifierValue(),
                    isCard ? request.cardNo() : null,
                    false);
        }
    }
}
