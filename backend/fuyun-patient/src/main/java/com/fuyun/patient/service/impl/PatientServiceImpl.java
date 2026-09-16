package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.api.PatientFrozenPayload;
import com.fuyun.patient.api.PatientUnfrozenPayload;
import com.fuyun.patient.api.PatientUpdatedPayload;
import com.fuyun.patient.cache.PatientCacheService;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.convert.PatientConverter;
import com.fuyun.patient.dto.PatientSearchQuery;
import com.fuyun.patient.dto.PatientUpdateRequest;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.internal.PatientDomainEvent;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.mapper.PatientMapper;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.service.PrivacyMaskService;
import com.fuyun.patient.vo.PatientVO;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 患者主索引服务实现（patient.patient 主表）：档案详情/检索（脱敏出口）、主数据部分更新、
 * 冻结/解冻状态机（NORMAL/FROZEN/MERGED）承载于此；api PatientContextResolver 实现（读侧归一
 * 解析 + 两级缓存消费）随 Task 7 在本类扩充。
 *
 * <p>敏感红线：证件号/手机号/住址明文仅在本类更新比对与解密回填生命周期内存活，禁入日志；
 * 出参一律经 PrivacyMaskService 脱敏（M02 红线 3）。
 */
@Slf4j
public class PatientServiceImpl extends ServiceImpl<PatientMapper, Patient> implements IPatientService {

    /** 加密构件（更新路径敏感字段重加密与检索盲索引），构造器注入 */
    private final PatientFieldCrypto crypto;

    /** 脱敏引擎（详情/检索出参统一出口），构造器注入 */
    private final PrivacyMaskService privacyMaskService;

    /** 两级缓存（冻结/解冻/更新即时失效本实例），构造器注入 */
    private final PatientCacheService cacheService;

    /** 应用事件发布（updated/frozen/unfrozen 事务内发布），构造器注入 */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 全参构造器（装配归 PatientWebConfig @Import）。
     *
     * @param crypto             加密构件，非空
     * @param privacyMaskService 脱敏引擎，非空
     * @param cacheService       两级缓存，非空
     * @param eventPublisher     Spring 应用事件发布器，非空（容器供给）
     */
    public PatientServiceImpl(
            PatientFieldCrypto crypto,
            PrivacyMaskService privacyMaskService,
            PatientCacheService cacheService,
            ApplicationEventPublisher eventPublisher) {
        this.crypto = crypto;
        this.privacyMaskService = privacyMaskService;
        this.cacheService = cacheService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 档案详情（脱敏）。
     *
     * @param patientId 患者主索引，非空
     * @return 脱敏出参，非空
     * @throws BizException PAT-1001 档案不存在
     */
    @Override
    @Transactional(readOnly = true)
    public PatientVO getDetail(long patientId) {
        Patient entity = getById(patientId);
        if (entity == null) {
            throw new BizException(PatientErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "患者档案不存在");
        }
        return maskOne(entity);
    }

    /**
     * 患者检索（keyword 形态分派：证件号→盲索引等值、手机号→盲索引等值、否则姓名模糊；空关键词=空数据页）。
     *
     * @param query 查询条件，非空
     * @return 脱敏分页，非空
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<PatientVO> search(PatientSearchQuery query) {
        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        // 空/空白关键词短路空数据页：不触库（防无 WHERE 全表分页与全表 COUNT——端点 javadoc「防全表拉取」承诺的实现落点）
        if (keyword.isEmpty()) {
            return PageResult.of(List.of(), query.page(), query.size(), 0);
        }
        boolean byIdCard = keyword.matches("\\d{15}|\\d{17}[0-9Xx]");
        boolean byMobile = keyword.matches("1\\d{10}");
        Page<Patient> page = new Page<>(query.page() + 1, query.size());
        Page<Patient> result = lambdaQuery()
                .eq(byIdCard, Patient::getIdCardNoHash, crypto.hash(keyword))
                .eq(byMobile, Patient::getMobileHash, crypto.hash(keyword))
                .like(!byIdCard && !byMobile, Patient::getName, keyword)
                .orderByDesc(Patient::getPatientId)
                .page(page);
        // maskOne 内部已经过脱敏引擎（审查 M3：去掉外层二次 applyAll——掩码函数幂等无正确性问题，但双重调用冗余易误导）
        List<PatientVO> masked = result.getRecords().stream().map(this::maskOne).toList();
        return PageResult.of(masked, query.page(), query.size(), result.getTotal());
    }

    /**
     * 主数据部分更新（MERGED 档案拒绝——主数据以主档为准；变更字段重加密落库）。
     *
     * @param patientId 患者主索引，非空
     * @param request   更新请求，非空
     * @return 变更字段名清单，非空
     * @throws BizException PAT-1001/PAT-1003
     */
    @Override
    @Transactional
    public List<String> update(long patientId, PatientUpdateRequest request) {
        Patient entity = getById(patientId);
        if (entity == null) {
            throw new BizException(PatientErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "患者档案不存在");
        }
        if ("MERGED".equals(entity.getStatus())) {
            throw new BizException(PatientErrorCode.PATIENT_ALREADY_MERGED, HttpStatus.CONFLICT, "已合并档案不可修改，请操作主档");
        }
        List<String> changed = new ArrayList<>();
        if (notBlank(request.name()) && !request.name().equals(entity.getName())) {
            entity.setName(request.name());
            entity.setNamePinyin(PinyinStub.toPinyin(request.name()));
            changed.add("name");
        }
        if (notBlank(request.sex()) && !request.sex().equals(entity.getSex())) {
            entity.setSex(request.sex());
            changed.add("sex");
        }
        if (notBlank(request.birthDate())
                && !LocalDate.parse(request.birthDate()).equals(entity.getBirthDate())) {
            entity.setBirthDate(LocalDate.parse(request.birthDate()));
            changed.add("birthDate");
        }
        if (notBlank(request.ethnicity()) && !request.ethnicity().equals(entity.getEthnicity())) {
            entity.setEthnicity(request.ethnicity());
            changed.add("ethnicity");
        }
        if (notBlank(request.maritalStatus()) && !request.maritalStatus().equals(entity.getMaritalStatus())) {
            entity.setMaritalStatus(request.maritalStatus());
            changed.add("maritalStatus");
        }
        if (notBlank(request.occupation()) && !request.occupation().equals(entity.getOccupation())) {
            entity.setOccupation(request.occupation());
            changed.add("occupation");
        }
        if (notBlank(request.bloodType()) && !request.bloodType().equals(entity.getBloodType())) {
            entity.setBloodType(request.bloodType());
            changed.add("bloodType");
        }
        if (notBlank(request.mobile()) && !crypto.hash(request.mobile()).equals(entity.getMobileHash())) {
            entity.setMobileCipher(crypto.encrypt(request.mobile()));
            entity.setMobileHash(crypto.hash(request.mobile()));
            changed.add("mobile");
        }
        // 取舍注明（审查 M7）：address 无盲索引列（密文不可比），不判变更即写库——值未变也发 updated
        // 事件属可接受噪声代价；如需变更检测须新增明文 hash 列，随需求演进另立迁移。
        if (notBlank(request.address())) {
            entity.setAddressCipher(crypto.encrypt(request.address()));
            changed.add("address");
        }
        if (changed.isEmpty()) {
            return changed;
        }
        updateById(entity);
        cacheService.evictView(patientId);
        eventPublisher.publishEvent(new PatientDomainEvent(
                PatientMessagingConstants.EVENT_UPDATED, new PatientUpdatedPayload(patientId, List.copyOf(changed))));
        log.info("患者主数据更新：patientId={}，changedFields={}", patientId, changed);
        return changed;
    }

    /**
     * 冻结档案（NORMAL→FROZEN；MERGED 不可变更状态）。
     *
     * @param patientId 患者主索引，非空
     * @param reason    冻结原因，非空
     * @throws BizException PAT-1001/PAT-1003/PAT-1004
     */
    @Override
    @Transactional
    public void freeze(long patientId, String reason) {
        Patient entity = requireTransitionable(patientId);
        if ("FROZEN".equals(entity.getStatus())) {
            throw new BizException(PatientErrorCode.PATIENT_ALREADY_FROZEN, HttpStatus.CONFLICT, "患者已处于冻结状态");
        }
        entity.setStatus("FROZEN");
        updateById(entity);
        cacheService.evictView(patientId);
        eventPublisher.publishEvent(new PatientDomainEvent(
                PatientMessagingConstants.EVENT_FROZEN, new PatientFrozenPayload(patientId, reason)));
        log.info("患者已冻结：patientId={}", patientId);
    }

    /**
     * 解冻档案（FROZEN→NORMAL）。
     *
     * @param patientId 患者主索引，非空
     * @throws BizException PAT-1001/PAT-1005
     */
    @Override
    @Transactional
    public void unfreeze(long patientId) {
        Patient entity = getById(patientId);
        if (entity == null) {
            throw new BizException(PatientErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "患者档案不存在");
        }
        if (!"FROZEN".equals(entity.getStatus())) {
            throw new BizException(PatientErrorCode.PATIENT_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "仅冻结档案可解冻");
        }
        entity.setStatus("NORMAL");
        updateById(entity);
        cacheService.evictView(patientId);
        eventPublisher.publishEvent(new PatientDomainEvent(
                PatientMessagingConstants.EVENT_UNFROZEN, new PatientUnfrozenPayload(patientId)));
        log.info("患者已解冻：patientId={}", patientId);
    }

    /** 实体→脱敏出参（解密敏感列后交脱敏引擎；明文仅在本方法生命周期内存活） */
    private PatientVO maskOne(Patient entity) {
        PatientVO vo = Mappers.getMapper(PatientConverter.class).toVO(entity);
        vo.setIdCardNo(crypto.decrypt(entity.getIdCardNoCipher()));
        vo.setMobile(crypto.decrypt(entity.getMobileCipher()));
        vo.setAddress(crypto.decrypt(entity.getAddressCipher()));
        return privacyMaskService.applyAll(List.of(vo)).get(0);
    }

    /** 状态可变更守卫：档案存在且非 MERGED（冻结/其他状态机共用） */
    private Patient requireTransitionable(long patientId) {
        Patient entity = getById(patientId);
        if (entity == null) {
            throw new BizException(PatientErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "患者档案不存在");
        }
        if ("MERGED".equals(entity.getStatus())) {
            throw new BizException(PatientErrorCode.PATIENT_ALREADY_MERGED, HttpStatus.CONFLICT, "已合并档案不可变更状态");
        }
        return entity;
    }

    /** 非空白判定（请求字段可空语义统一收口） */
    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
