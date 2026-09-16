package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.OngoingVisitQuery;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.api.PatientMergedPayload;
import com.fuyun.patient.api.PatientSplitPayload;
import com.fuyun.patient.cache.PatientCacheService;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.convert.PatientConverter;
import com.fuyun.patient.dto.MergeCreateRequest;
import com.fuyun.patient.entity.MergeRecord;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.internal.PatientDomainEvent;
import com.fuyun.patient.mapper.MergeRecordMapper;
import com.fuyun.patient.service.IMergeRecordService;
import com.fuyun.patient.service.IPatientIdentifierService;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.vo.MergeRecordVO;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合并/拆分状态机实现（patient.merge_record 主表；M02 §3.3 指针映射定稿口径）。
 *
 * <p>SPI 前置检查（拍板 3 冻结语义）：Spring 注入 {@code List<OngoingVisitQuery>}——空清单
 * （M03/M04 未注册）warn 放行；非空时任一方在途即 PAT-1006 阻断。合并执行单事务轻量原子
 * （仅主索引层变更，历史业务行零改写）；事件在事务内发应用事件、AFTER_COMMIT 出 MQ（A.4.2-7）。
 *
 * <p>状态机：PROCESSING→COMPLETED / FAILED（前置失败或广播异常）；FAILED 经 approve 重跑执行序列
 * （置回 PROCESSING 后执行）；COMPLETED→REVERSED 终态（拆分，按 pre_snapshot 回挂标识）。
 */
@Slf4j
public class MergeRecordServiceImpl extends ServiceImpl<MergeRecordMapper, MergeRecord> implements IMergeRecordService {

    /** 状态词表：合并进行中（MergeStatus；FAILED 重试时置回同值后执行） */
    private static final String STATUS_PROCESSING = "PROCESSING";

    /** 状态词表：合并完成（MergeStatus） */
    private static final String STATUS_COMPLETED = "COMPLETED";

    /** 状态词表：已拆分恢复终态（MergeStatus） */
    private static final String STATUS_REVERSED = "REVERSED";

    private final IPatientService patientService;

    private final IPatientIdentifierService identifierService;

    private final PatientCacheService cacheService;

    private final ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper;

    /** SPI 实现清单（Spring 收集注入；空清单=无注册实现，拍板 3 放行语义） */
    private final List<OngoingVisitQuery> ongoingVisitQueries;

    /**
     * 全参构造器（装配归 PatientWebConfig @Import；SPI 清单由 Spring 按类型收集全部实现 Bean）。
     *
     * @param patientService      患者主表服务，非空
     * @param identifierService   标识服务（重挂/回挂），非空
     * @param cacheService        两级缓存（合并/拆分即时失效），非空
     * @param eventPublisher      应用事件发布器，非空
     * @param objectMapper        快照序列化器（全局定制实例），非空
     * @param ongoingVisitQueries SPI 实现清单，非 null（可为空清单）
     */
    public MergeRecordServiceImpl(
            IPatientService patientService,
            IPatientIdentifierService identifierService,
            PatientCacheService cacheService,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            List<OngoingVisitQuery> ongoingVisitQueries) {
        this.patientService = patientService;
        this.identifierService = identifierService;
        this.cacheService = cacheService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.ongoingVisitQueries = ongoingVisitQueries;
    }

    /**
     * 发起合并（建 PROCESSING；双人角色第一步，经办人取当前操作人、无登录上下文回退 system）。
     *
     * @param request 发起请求，非空
     * @return 合并记录出参，非空
     * @throws BizException PAT-1001/PAT-1003/PAT-1005/PAT-1008
     */
    @Override
    @Transactional
    public MergeRecordVO create(MergeCreateRequest request) {
        if (request.survivorPatientId().equals(request.mergedPatientId())) {
            throw new BizException(PatientErrorCode.MERGE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "主档与从档不得为同一档案");
        }
        Patient survivor = requireExists(request.survivorPatientId());
        Patient merged = requireExists(request.mergedPatientId());
        if ("MERGED".equals(merged.getStatus())) {
            throw new BizException(PatientErrorCode.PATIENT_ALREADY_MERGED, HttpStatus.CONFLICT, "从档已是合并状态");
        }
        if ("FROZEN".equals(survivor.getStatus())) {
            throw new BizException(PatientErrorCode.PATIENT_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "冻结档案不可作为主档");
        }
        MergeRecord record = new MergeRecord();
        record.setSurvivorPatientId(request.survivorPatientId());
        record.setMergedPatientId(request.mergedPatientId());
        record.setMergeReason(request.mergeReason());
        record.setStatus(STATUS_PROCESSING);
        String operator = OperatorContextHolder.get();
        record.setOperator(operator == null ? "system" : operator);
        save(record);
        log.info(
                "合并发起：id={}，survivor={}，merged={}",
                record.getId(),
                record.getSurvivorPatientId(),
                record.getMergedPatientId());
        return toVO(record);
    }

    /**
     * 审批并执行合并（双人角色 + SPI 前置检查 + 指针映射五步；FAILED 记录重跑执行序列）。
     *
     * @param id       合并记录 id，非空
     * @param operator 审批操作人，非空
     * @return 合并记录出参，非空
     * @throws BizException PAT-1006/PAT-1007/PAT-1008
     */
    @Override
    @Transactional
    public MergeRecordVO approve(long id, String operator) {
        MergeRecord record = requireRecord(id);
        // 终态守卫：COMPLETED/REVERSED 不可再审批（PROCESSING/FAILED 可执行——FAILED 即重试路径）
        if (STATUS_COMPLETED.equals(record.getStatus()) || STATUS_REVERSED.equals(record.getStatus())) {
            throw new BizException(PatientErrorCode.MERGE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "合并记录已终态，不可再审批");
        }
        if (operator.equals(record.getOperator())) {
            throw new BizException(PatientErrorCode.MERGE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "双人角色约束：审批人不得与经办人相同");
        }
        // SPI 在途检查（事务内仅本地查询，M03/M04 经 OngoingVisitQuery 注册）
        checkOngoingVisits(record.getSurvivorPatientId(), record.getMergedPatientId());
        record.setApprovedBy(operator);
        record.setStatus(STATUS_PROCESSING);
        executeMerge(record);
        return toVO(record);
    }

    /**
     * 合并执行（快照 → 补齐 → 重挂 → 从档 MERGED → COMPLETED → 缓存失效 → merged 事件）。
     *
     * @param record PROCESSING 记录，非空
     */
    private void executeMerge(MergeRecord record) {
        Patient survivor = requireExists(record.getSurvivorPatientId());
        Patient merged = requireExists(record.getMergedPatientId());
        // ①快照（从档字段 + 标识挂接清单；拆分回滚依据——历史数据零改写原则下的可逆锚点）
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("name", merged.getName());
        snapshot.put("sex", merged.getSex());
        snapshot.put("birthDate", String.valueOf(merged.getBirthDate()));
        snapshot.put("mobile", merged.getMobileHash());
        snapshot.put("address", merged.getAddressCipher() != null);
        snapshot.put(
                "identifiers",
                identifierService.listByPatient(merged.getPatientId()).stream()
                        .map(i -> Map.of(
                                "id", i.getId(),
                                "patientId", i.getPatientId(),
                                "isPrimary", Boolean.TRUE.equals(i.getIsPrimary())))
                        .toList());
        try {
            record.setPreSnapshot(objectMapper.writeValueAsString(snapshot));
        } catch (JsonProcessingException e) {
            // 快照是拆分可逆的唯一依据：序列化失败即拒绝执行合并（fail-fast 防不可拆合并）
            throw new IllegalStateException("合并快照序列化失败", e);
        }
        // ②主档空字段补齐（字段级择优：主档空且从档非空才补，双方原值已在快照）
        if (survivor.getMobileHash() == null && merged.getMobileHash() != null) {
            survivor.setMobileCipher(merged.getMobileCipher());
            survivor.setMobileHash(merged.getMobileHash());
        }
        if (survivor.getAddressCipher() == null && merged.getAddressCipher() != null) {
            survivor.setAddressCipher(merged.getAddressCipher());
        }
        patientService.updateById(survivor);
        // ③从档标识整批重挂主档（原挂接在快照；is_primary 随行保留，历史业务行零改写仅改指针归属）
        for (PatientIdentifier identifier : identifierService.listByPatient(merged.getPatientId())) {
            identifier.setPatientId(survivor.getPatientId());
            identifierService.updateById(identifier);
        }
        // ④从档置 MERGED + 指针（读侧解析沿 merged_into 收敛）
        merged.setStatus("MERGED");
        merged.setMergedIntoPatientId(survivor.getPatientId());
        patientService.updateById(merged);
        // ⑤COMPLETED + 缓存失效 + 事件（事务内发应用事件，发布器 AFTER_COMMIT 出 MQ）
        record.setStatus(STATUS_COMPLETED);
        record.setCompletedAt(OffsetDateTime.now());
        updateById(record);
        cacheService.evictView(survivor.getPatientId());
        cacheService.evictView(merged.getPatientId());
        eventPublisher.publishEvent(new PatientDomainEvent(
                PatientMessagingConstants.EVENT_MERGED,
                new PatientMergedPayload(survivor.getPatientId(), merged.getPatientId())));
        log.info("合并完成：id={}，survivor={}，merged={}", record.getId(), survivor.getPatientId(), merged.getPatientId());
    }

    /**
     * 拆分恢复（COMPLETED→REVERSED 终态；标识按快照回挂）。
     *
     * @param id     合并记录 id，非空
     * @param reason 拆分原因，非空
     * @return 合并记录出参，非空
     * @throws BizException PAT-1007/PAT-1008
     */
    @Override
    @Transactional
    public MergeRecordVO split(long id, String reason) {
        MergeRecord record = requireRecord(id);
        if (!STATUS_COMPLETED.equals(record.getStatus())) {
            throw new BizException(PatientErrorCode.MERGE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "仅已完成合并可拆分");
        }
        Patient survivor = requireExists(record.getSurvivorPatientId());
        Patient merged = requireExists(record.getMergedPatientId());
        // 标识按快照回挂（快照 identifier 清单原 patientId=从档；快照损坏显式暴露拒拆）
        List<Long> snapshotIds = extractSnapshotIdentifierIds(record.getPreSnapshot());
        for (Long identifierId : snapshotIds) {
            PatientIdentifier identifier = identifierService.getById(identifierId);
            if (identifier != null) {
                identifier.setPatientId(merged.getPatientId());
                identifierService.updateById(identifier);
            }
        }
        // 从档恢复 NORMAL 并清合并指针（与 executeMerge ④ 互逆）
        merged.setStatus("NORMAL");
        merged.setMergedIntoPatientId(null);
        patientService.updateById(merged);
        patientService.updateById(survivor);
        record.setStatus(STATUS_REVERSED);
        record.setReversedAt(OffsetDateTime.now());
        record.setReverseReason(reason);
        updateById(record);
        cacheService.evictView(survivor.getPatientId());
        cacheService.evictView(merged.getPatientId());
        eventPublisher.publishEvent(new PatientDomainEvent(
                PatientMessagingConstants.EVENT_SPLIT,
                new PatientSplitPayload(merged.getPatientId(), survivor.getPatientId())));
        log.info(
                "拆分恢复完成：id={}，restored={}，survivor={}", record.getId(), merged.getPatientId(), survivor.getPatientId());
        return toVO(record);
    }

    /**
     * SPI 在途检查（拍板 3：空清单 warn 放行；命中任一方在途即阻断）。
     *
     * @param survivorId 主档 id，非空
     * @param mergedId   从档 id，非空
     * @throws BizException PAT-1006 任一 SPI 实现报告在途就诊
     */
    private void checkOngoingVisits(long survivorId, long mergedId) {
        if (ongoingVisitQueries.isEmpty()) {
            log.warn("在途就诊查询 SPI 无注册实现，合并前置检查放行（拍板 3 冻结语义）：survivor={}，merged={}", survivorId, mergedId);
            return;
        }
        for (OngoingVisitQuery query : ongoingVisitQueries) {
            if (query.hasOngoingVisit(survivorId) || query.hasOngoingVisit(mergedId)) {
                throw new BizException(
                        PatientErrorCode.MERGE_BLOCKED_BY_ONGOING_VISIT, HttpStatus.CONFLICT, "存在在途就诊，禁止合并（请先完结就诊）");
            }
        }
    }

    /**
     * 快照中的标识 id 清单提取（JSON 结构见 executeMerge ①）。
     *
     * @param preSnapshot 快照 JSON 全文，非空
     * @return 快照内的标识 id 清单（空快照清单返回空列表）
     * @throws IllegalStateException 快照 JSON 损坏（数据异常显式暴露，拒绝继续拆分）
     */
    private List<Long> extractSnapshotIdentifierIds(String preSnapshot) {
        try {
            JsonNode root = objectMapper.readTree(preSnapshot);
            // 逐节点收集（Jackson 2.x 全版本可用写法，不依赖 2.17+ valueStream）
            List<Long> ids = new ArrayList<>();
            for (JsonNode node : root.path("identifiers")) {
                ids.add(node.path("id").asLong());
            }
            return ids;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("合并快照解析失败（数据损坏）", e);
        }
    }

    /**
     * 档案存在守卫。
     *
     * @param patientId 患者主索引，非空
     * @return 档案实体，非空
     * @throws BizException PAT-1001（404）档案不存在
     */
    private Patient requireExists(long patientId) {
        Patient entity = patientService.getById(patientId);
        if (entity == null) {
            throw new BizException(PatientErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "患者档案不存在");
        }
        return entity;
    }

    /**
     * 合并记录存在守卫。
     *
     * @param id 合并记录 id，非空
     * @return 合并记录实体，非空
     * @throws BizException PAT-1007（404）合并记录不存在
     */
    private MergeRecord requireRecord(long id) {
        MergeRecord record = getById(id);
        if (record == null) {
            throw new BizException(PatientErrorCode.MERGE_RECORD_NOT_FOUND, HttpStatus.NOT_FOUND, "合并记录不存在");
        }
        return record;
    }

    /**
     * 记录→出参（preSnapshot 快照全文不出参——审计经库内查询）。
     *
     * @param record 合并记录实体，非空
     * @return 合并记录出参，非空
     */
    private MergeRecordVO toVO(MergeRecord record) {
        return Mappers.getMapper(PatientConverter.class).toVO(record);
    }
}
