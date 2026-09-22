package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.dto.NurseAssignmentRequest;
import com.fuyun.nursing.dto.WardPatientRegisterRequest;
import com.fuyun.nursing.dto.WardPatientRemoveRequest;
import com.fuyun.nursing.entity.NurseAssignment;
import com.fuyun.nursing.entity.NursingWardConfig;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.enums.AssignmentType;
import com.fuyun.nursing.enums.NursingLevel;
import com.fuyun.nursing.enums.WardPatientSource;
import com.fuyun.nursing.enums.WardPatientStatus;
import com.fuyun.nursing.mapper.NurseAssignmentMapper;
import com.fuyun.nursing.mapper.NursingWardConfigMapper;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.NurseAssignmentVO;
import com.fuyun.nursing.vo.WardConfigVO;
import com.fuyun.nursing.vo.WardConfigVO.ShiftDefinition;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import com.fuyun.nursing.vo.WardPatientVO;
import com.fuyun.patient.api.AllergyChecker;
import com.fuyun.patient.api.AllergyItem;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import com.fuyun.patient.api.VisitIdValidator;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 病区元数据域服务实现（V801 三表业务面）。入区登记守卫链（GC16 过渡通道强制）：
 * VisitIdValidator 结构校验 → PatientContextResolver 拦截（FROZEN 拒 / MERGED 收敛主档）→
 * 床位占用前置检查 → insert（双唯一约束兜底转 NS-1002）。移出走单表单语句 CAS（GC38 四护栏），
 * 订阅面（合并/拆分/过敏刷新）归 internal 监听器直驱 mapper 条件更新。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class WardMetaServiceImpl extends ServiceImpl<NursingWardPatientMapper, NursingWardPatient>
        implements IWardMetaService {

    /** 移出/撤销等无登录上下文场景的操作者回退值（与 V801 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    private final NurseAssignmentMapper assignmentMapper;

    private final NursingWardConfigMapper wardConfigMapper;

    private final PatientContextResolver patientContextResolver;

    private final AllergyChecker allergyChecker;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param wardPatientMapper      病区患者视图 mapper，非空；ServiceImpl 基座 mapper
     * @param assignmentMapper       责任护士分配 mapper，非空
     * @param wardConfigMapper       病区护理配置 mapper，非空
     * @param patientContextResolver 患者上下文解析（patient api），非空；入区归一/拦截
     * @param allergyChecker         过敏项嵌查（patient api），非空；详情卡实时过敏面
     * @param objectMapper           JSON 解析器（Boot 自动装配），非空；JSONB 列结构化
     */
    public WardMetaServiceImpl(
            NursingWardPatientMapper wardPatientMapper,
            NurseAssignmentMapper assignmentMapper,
            NursingWardConfigMapper wardConfigMapper,
            PatientContextResolver patientContextResolver,
            AllergyChecker allergyChecker,
            ObjectMapper objectMapper) {
        this.assignmentMapper = assignmentMapper;
        this.wardConfigMapper = wardConfigMapper;
        this.patientContextResolver = patientContextResolver;
        this.allergyChecker = allergyChecker;
        this.objectMapper = objectMapper;
    }

    /**
     * 入区登记（P1 过渡通道，幂等 upsert）：守卫链见类注。同 visit_id 已在区时视图属性全等
     * 直接返回（零写入）；有差异更新既有行并按新床位校验占用（床位未变不复查）。
     *
     * @param req 登记入参，非空；来源：操作者工作站表单
     * @return 登记行出参，非空
     * @throws BizException NS-1003（400 visitId 结构不合法）/ NS-1004（409 档案冻结）/
     *                      NS-1002（409 床位占用或唯一约束冲突）/ NS-1019（400 护理级别 code 非法）
     */
    @Override
    @Transactional
    public WardPatientVO register(WardPatientRegisterRequest req) {
        // 守卫链①：visit_id 结构校验（I 型 14 位；M05 仅结构校验，签发权威在 M04）
        if (!VisitIdValidator.isValid(req.visitId())) {
            throw new BizException(
                    NursingErrorCode.VISIT_ID_INVALID, HttpStatus.BAD_REQUEST, "住院就诊号结构不合法：" + req.visitId());
        }
        // 守卫链①′：护理级别 code 显式格式校验（缺省 NORMAL，与 V801 列默认一致）
        NursingLevel level =
                req.nursingLevel() == null ? NursingLevel.NORMAL : NursingLevel.fromCode(req.nursingLevel());
        if (level == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "护理级别 code 非法：" + req.nursingLevel());
        }
        // 守卫链②：档案归一/拦截（FROZEN 拒新就诊；MERGED 按 resolvedPatientId 收敛主档，CF-3）
        PatientContextView ctx = patientContextResolver.resolve(req.patientId());
        if (ctx.blocked()) {
            throw new BizException(
                    NursingErrorCode.PATIENT_BLOCKED,
                    HttpStatus.CONFLICT,
                    "患者档案已冻结，禁止入区登记：visitId=" + req.visitId() + "，原因：" + ctx.blockReason());
        }
        NursingWardPatient existing = requireInWardByVisit(req.visitId());
        String operator = operator();
        if (existing != null) {
            // 幂等 upsert 分支：视图属性全等零写入直接返回；有差异更新既有行（insert 禁触）
            if (viewAttrsEqual(existing, req, level.getCode())) {
                log.info(
                        "入区登记幂等命中（零写入）：visitId={}，wardId={}，bedNo={}",
                        req.visitId(),
                        req.wardId(),
                        existing.getBedNo());
                return WardPatientVO.from(existing);
            }
            // 床位变更时按新床位校验占用（未变更不复查；uk_ward_patient_bed 兜底并发）
            if (!Objects.equals(existing.getBedNo(), req.bedNo())) {
                assertBedFree(req.wardId(), req.bedNo());
            }
            existing.setBedNo(req.bedNo());
            existing.setPatientName(req.patientName());
            existing.setGender(req.gender());
            existing.setAge(req.age());
            existing.setNursingLevel(level.getCode());
            existing.setConditionTags(orEmpty(req.conditionTags()));
            existing.setUpdatedBy(operator);
            // 数据库写操作：视图属性更新既有行（幂等 upsert 写面，唯一行不新建）
            baseMapper.updateById(existing);
            log.info(
                    "入区登记视图属性更新：visitId={}，wardId={}，bedNo={}，nursingLevel={}",
                    req.visitId(),
                    req.wardId(),
                    req.bedNo(),
                    level.getCode());
            return WardPatientVO.from(existing);
        }
        // 守卫链③：床位占用前置检查（双唯一约束之一的业务侧前置，禁裸插吞异常）
        assertBedFree(req.wardId(), req.bedNo());
        NursingWardPatient row = new NursingWardPatient();
        row.setWardId(req.wardId());
        row.setBedNo(req.bedNo());
        row.setPatientId(ctx.resolvedPatientId());
        row.setVisitId(req.visitId());
        row.setPatientName(req.patientName());
        row.setGender(req.gender());
        row.setAge(req.age());
        row.setNursingLevel(level.getCode());
        row.setConditionTags(orEmpty(req.conditionTags()));
        row.setAllergyFlag(false);
        row.setRiskFlags("");
        // 入区时间取登记时点服务器时间（补录入院时间属 ADT 写能力，端点冻结清单禁项）
        row.setAdmittedAt(OffsetDateTime.now());
        row.setStatus(WardPatientStatus.IN_WARD.getCode());
        row.setSource(WardPatientSource.MANUAL.getCode());
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        try {
            // 数据库写操作：入区登记落库（uk_ward_patient_visit/uk_ward_patient_bed 双唯一约束兜底）
            baseMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 唯一约束冲突兜底转业务拒绝（并发登记同 visit_id/同床位场景）
            throw new BizException(
                    NursingErrorCode.BED_OCCUPIED,
                    HttpStatus.CONFLICT,
                    "入区登记唯一性冲突（就诊号或床位已占用）：visitId=" + req.visitId() + "，wardId=" + req.wardId() + "，bedNo="
                            + req.bedNo());
        }
        log.info(
                "入区登记完成：visitId={}，wardId={}，bedNo={}，patientId={}，nursingLevel={}",
                req.visitId(),
                req.wardId(),
                req.bedNo(),
                row.getPatientId(),
                level.getCode());
        return WardPatientVO.from(row);
    }

    /**
     * 移出病区一览（GC38 四护栏）：单表单语句 CAS（IN_WARD→REMOVED + 操作者审计列），零外发、
     * 无级联、reason 仅入日志留痕不落库；影响 0 行定性 NS-1001。零回读：返回仅携 visitId 的确认出参。
     *
     * @param visitId 住院就诊号，非空；来源：路径参数
     * @param req     移出入参（reason 留痕），非空；来源：操作者录入
     * @return 确认出参（仅 visitId 有值），非空
     * @throws BizException NS-1001（404 在区行不存在或已移出）
     */
    @Override
    @Transactional
    public WardPatientVO remove(String visitId, WardPatientRemoveRequest req) {
        String operator = operator();
        // 数据库写操作：单表单语句 CAS（触达最小护栏；updated_at 由库触发器维护）
        int rows = baseMapper.casRemove(visitId, operator);
        if (rows == 0) {
            throw new BizException(
                    NursingErrorCode.WARD_PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "病区在区患者不存在：" + visitId);
        }
        // 零外发护栏：仅日志留痕（reason 不落库、不发事件、无任何跨模块调用）
        log.info("移出病区一览：visitId={}，operator={}，reason={}", visitId, operator, req.reason());
        return new WardPatientVO(visitId, null, null, null, null, null);
    }

    /**
     * 病区在区患者一览（床位序）：仅 IN_WARD 行，DB 侧按 bed_no、admitted_at 升序。
     *
     * @param wardId 病区编码，非空；来源：查询参数
     * @return 在区行出参清单（无行返回空清单，非 null）
     */
    @Override
    @Transactional(readOnly = true)
    public List<WardPatientVO> listByWard(String wardId) {
        // 数据库读操作：在区行一览（床位序；REMOVED 与他病区行由条件排除）
        List<NursingWardPatient> rows = baseMapper.selectList(Wrappers.<NursingWardPatient>lambdaQuery()
                .eq(NursingWardPatient::getWardId, wardId)
                .eq(NursingWardPatient::getStatus, WardPatientStatus.IN_WARD.getCode())
                .orderByAsc(NursingWardPatient::getBedNo)
                .orderByAsc(NursingWardPatient::getAdmittedAt));
        return rows.stream().map(WardPatientVO::from).toList();
    }

    /**
     * 患者详情卡聚合：在区行 + 过敏实时嵌查（AllergyChecker）+ 当班责任护士（配置班次按时钟判定）
     * + 在途任务占位（Task 7 补填）。不含体征摘要（前端另调体征查询组装，防服务间循环依赖）。
     *
     * @param visitId 住院就诊号，非空；来源：路径参数
     * @return 详情卡出参，非空
     * @throws BizException NS-1001（404 在区行不存在）
     */
    @Override
    @Transactional(readOnly = true)
    public WardPatientDetailVO detail(String visitId) {
        NursingWardPatient row = requireInWardByVisit(visitId);
        if (row == null) {
            throw new BizException(
                    NursingErrorCode.WARD_PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "病区在区患者不存在：" + visitId);
        }
        // 数据库读操作：当班责任护士（shift_code 由病区配置班次按时钟判定，无配置行则不过滤班次）
        String shiftCode = currentShiftCode(row.getWardId());
        LambdaQueryWrapper<NurseAssignment> wrapper = Wrappers.<NurseAssignment>lambdaQuery()
                .eq(NurseAssignment::getWardId, row.getWardId())
                .eq(NurseAssignment::getStatus, "ACTIVE")
                .le(NurseAssignment::getValidFrom, LocalDate.now())
                .and(w -> w.isNull(NurseAssignment::getValidTo).or().ge(NurseAssignment::getValidTo, LocalDate.now()))
                .orderByAsc(NurseAssignment::getBedNo);
        if (shiftCode != null) {
            wrapper.eq(NurseAssignment::getShiftCode, shiftCode);
        }
        // 数据库读操作：当日生效分配清单
        List<NurseAssignment> assignments = assignmentMapper.selectList(wrapper);
        // 第三方接口调用：patient 过敏项实时嵌查（详情卡过敏明细，allergy_flag 为订阅缓存镜像）
        List<AllergyItem> allergies = allergyChecker.listActiveAllergies(row.getPatientId());
        log.info(
                "患者详情卡聚合：visitId={}，wardId={}，bedNo={}，shiftCode={}，allergies={}，assignments={}",
                visitId,
                row.getWardId(),
                row.getBedNo(),
                shiftCode,
                allergies.size(),
                assignments.size());
        return new WardPatientDetailVO(
                row.getWardId(),
                row.getBedNo(),
                row.getPatientId(),
                row.getVisitId(),
                row.getPatientName(),
                row.getGender(),
                row.getAge(),
                row.getNursingLevel(),
                row.getConditionTags(),
                row.getAllergyFlag(),
                row.getRiskFlags(),
                row.getAdmittedAt(),
                allergies,
                assignments.stream().map(NurseAssignmentVO::from).toList(),
                // Task 7 占位：执行域在途任务清单上线后补填并同步扩展断言
                List.of());
    }

    /**
     * 责任护士分配（FU-M05-01）：类型一致性校验（NS-1019）→ 床位/患者班次唯一前置查重（NS-1002）→
     * 落 ACTIVE 行（部分唯一索引兜底转 NS-1002）。
     *
     * @param req 分配入参，非空；来源：操作者护士站表单
     * @return 分配行出参，非空
     * @throws BizException NS-1019（400 类型与必填组件不一致或 code 非法）/
     *                      NS-1002（409 床位或患者班次分配重复）
     */
    @Override
    @Transactional
    public NurseAssignmentVO assign(NurseAssignmentRequest req) {
        // 守卫链①：分配类型与必填组件一致性（PRIMARY↔patient_id / BED↔bed_no 互斥必填）
        AssignmentType type = AssignmentType.fromCode(req.assignmentType());
        if (type == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "分配类型 code 非法：" + req.assignmentType());
        }
        if (type == AssignmentType.PRIMARY && (req.patientId() == null || notBlank(req.bedNo()))) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "责任组分配必须携责任患者且不得携床位：wardId=" + req.wardId() + "，nurseId=" + req.nurseId());
        }
        if (type == AssignmentType.BED && (!notBlank(req.bedNo()) || req.patientId() != null)) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "管床分配必须携床位且不得携责任患者：wardId=" + req.wardId() + "，nurseId=" + req.nurseId());
        }
        LocalDate validFrom = req.validFrom() == null ? LocalDate.now() : req.validFrom();
        // 守卫链②：班次唯一前置查重（uk_assignment_bed_shift / uk_assignment_patient_shift 的业务侧前置）
        Long dup = type == AssignmentType.BED
                ? assignmentMapper.selectCount(Wrappers.<NurseAssignment>lambdaQuery()
                        .eq(NurseAssignment::getWardId, req.wardId())
                        .eq(NurseAssignment::getBedNo, req.bedNo())
                        .eq(NurseAssignment::getShiftCode, req.shiftCode())
                        .eq(NurseAssignment::getValidFrom, validFrom)
                        .eq(NurseAssignment::getStatus, "ACTIVE"))
                : assignmentMapper.selectCount(Wrappers.<NurseAssignment>lambdaQuery()
                        .eq(NurseAssignment::getWardId, req.wardId())
                        .eq(NurseAssignment::getPatientId, req.patientId())
                        .eq(NurseAssignment::getShiftCode, req.shiftCode())
                        .eq(NurseAssignment::getValidFrom, validFrom)
                        .eq(NurseAssignment::getStatus, "ACTIVE"));
        if (dup != null && dup > 0) {
            throw new BizException(
                    NursingErrorCode.BED_OCCUPIED,
                    HttpStatus.CONFLICT,
                    "同班次分配重复：wardId=" + req.wardId() + "，shiftCode=" + req.shiftCode() + "，bedNo=" + req.bedNo()
                            + "，patientId=" + req.patientId());
        }
        String operator = operator();
        NurseAssignment row = new NurseAssignment();
        row.setWardId(req.wardId());
        row.setNurseId(req.nurseId());
        row.setAssignmentType(type.getCode());
        row.setShiftCode(req.shiftCode());
        row.setBedNo(req.bedNo());
        row.setPatientId(req.patientId());
        row.setValidFrom(validFrom);
        row.setValidTo(req.validTo());
        row.setStatus("ACTIVE");
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        try {
            // 数据库写操作：分配落库（ACTIVE 态部分唯一索引兜底并发）
            assignmentMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException(
                    NursingErrorCode.BED_OCCUPIED,
                    HttpStatus.CONFLICT,
                    "同班次分配唯一冲突：wardId=" + req.wardId() + "，shiftCode=" + req.shiftCode());
        }
        log.info(
                "责任护士分配完成：wardId={}，nurseId={}，type={}，shiftCode={}，bedNo={}，patientId={}",
                req.wardId(),
                req.nurseId(),
                type.getCode(),
                req.shiftCode(),
                req.bedNo(),
                req.patientId());
        return NurseAssignmentVO.from(row);
    }

    /**
     * 撤销责任分配（ACTIVE→CANCELLED，逻辑留痕不删行）。
     *
     * @param id 分配 id，非空；来源：路径参数
     * @throws BizException NS-1016（409 分配不存在或已撤销）
     */
    @Override
    @Transactional
    public void unassign(Long id) {
        // 数据库写操作：撤销 CAS（并发重复撤销 0 行定性拒绝）
        if (assignmentMapper.casCancel(id, operator()) != 1) {
            throw new BizException(NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "责任分配不存在或已撤销：assignmentId=" + id);
        }
        log.info("责任分配撤销：assignmentId={}，operator={}", id, operator());
    }

    /**
     * 病区指定班次的生效分配清单（当日有效窗口内；交接班 Task 9 消费）。
     *
     * @param wardId    病区编码，非空；来源：查询参数
     * @param shiftCode 班次 code，非空；来源：查询参数
     * @return 分配出参清单（无行返回空清单，非 null）
     */
    @Override
    @Transactional(readOnly = true)
    public List<NurseAssignmentVO> listAssignments(String wardId, String shiftCode) {
        // 数据库读操作：当日生效窗口内的班次分配（交接班快照引用）
        List<NurseAssignment> rows = assignmentMapper.selectList(Wrappers.<NurseAssignment>lambdaQuery()
                .eq(NurseAssignment::getWardId, wardId)
                .eq(NurseAssignment::getShiftCode, shiftCode)
                .eq(NurseAssignment::getStatus, "ACTIVE")
                .le(NurseAssignment::getValidFrom, LocalDate.now())
                .and(w -> w.isNull(NurseAssignment::getValidTo).or().ge(NurseAssignment::getValidTo, LocalDate.now()))
                .orderByAsc(NurseAssignment::getBedNo));
        return rows.stream().map(NurseAssignmentVO::from).toList();
    }

    /**
     * 病区护理配置读取：JSONB 列服务端结构化（体征频次 → Map；班次定义 → 班次清单）。
     *
     * @param wardId 病区编码，非空；来源：路径参数
     * @return 配置出参，非空
     * @throws BizException NS-1016（409 未知病区：无配置行）
     */
    @Override
    @Transactional(readOnly = true)
    public WardConfigVO wardConfig(String wardId) {
        NursingWardConfig config = requireConfig(wardId);
        Map<String, Integer> vitalFreqMinutes = parseVitalFreq(config, wardId);
        List<ShiftDefinition> shifts = parseShifts(config, wardId);
        log.info(
                "病区配置读取：wardId={}，shifts={}，iotAutocastEnabled={}",
                wardId,
                shifts.size(),
                config.getIotAutocastEnabled());
        return new WardConfigVO(
                config.getWardId(), vitalFreqMinutes, Boolean.TRUE.equals(config.getIotAutocastEnabled()), shifts);
    }

    /**
     * 风险标识追加回写（Task 8 评估高危消费）：追加缺失项、不重复追加、逗号分隔；
     * 已含该标识时零写入直接返回。
     *
     * @param visitId 住院就诊号，非空；来源：评估单载荷
     * @param flag    风险标识 code（如 FALL/PRESSURE），非空；来源：评估单高危结果
     * @throws BizException NS-1001（404 在区行不存在）
     */
    @Override
    @Transactional
    public void appendRiskFlag(String visitId, String flag) {
        NursingWardPatient row = requireInWardByVisit(visitId);
        if (row == null) {
            throw new BizException(
                    NursingErrorCode.WARD_PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "病区在区患者不存在：" + visitId);
        }
        // 已含即零写入（不重复追加护栏）
        List<String> flags = Arrays.stream(orEmpty(row.getRiskFlags()).split(","))
                .filter(s -> !s.isBlank())
                .toList();
        if (flags.contains(flag)) {
            log.info("风险标识已存在（零写入）：visitId={}，flag={}", visitId, flag);
            return;
        }
        String merged = String.join(",", flags) + (flags.isEmpty() ? flag : "," + flag);
        // 数据库写操作：风险标识条件回写（Task 8 评估高危结果）
        baseMapper.updateRiskFlags(visitId, merged, operator());
        log.info("风险标识追加：visitId={}，flag={}，riskFlags={}", visitId, flag, merged);
    }

    /** 按 visit_id 定位在区行（逻辑删由 @TableLogic 自动过滤；未命中返回 null 交调用方定性）。 */
    private NursingWardPatient requireInWardByVisit(String visitId) {
        return baseMapper.selectOne(Wrappers.<NursingWardPatient>lambdaQuery()
                .eq(NursingWardPatient::getVisitId, visitId)
                .eq(NursingWardPatient::getStatus, WardPatientStatus.IN_WARD.getCode()));
    }

    /** 床位占用前置检查：同病区同床位在区行存在即 NS-1002（uk_ward_patient_bed 前置）。 */
    private void assertBedFree(String wardId, String bedNo) {
        Long occupied = baseMapper.selectCount(Wrappers.<NursingWardPatient>lambdaQuery()
                .eq(NursingWardPatient::getWardId, wardId)
                .eq(NursingWardPatient::getBedNo, bedNo)
                .eq(NursingWardPatient::getStatus, WardPatientStatus.IN_WARD.getCode()));
        if (occupied != null && occupied > 0) {
            throw new BizException(
                    NursingErrorCode.BED_OCCUPIED,
                    HttpStatus.CONFLICT,
                    "床位已有在区患者：wardId=" + wardId + "，bedNo=" + bedNo);
        }
    }

    /** 幂等 upsert 全等判定：床位/展示名/性别/年龄/护理级别/病情标记全等即零写入。 */
    private boolean viewAttrsEqual(NursingWardPatient existing, WardPatientRegisterRequest req, String levelCode) {
        return Objects.equals(existing.getBedNo(), req.bedNo())
                && Objects.equals(existing.getPatientName(), req.patientName())
                && Objects.equals(existing.getGender(), req.gender())
                && Objects.equals(existing.getAge(), req.age())
                && Objects.equals(existing.getNursingLevel(), levelCode)
                && Objects.equals(existing.getConditionTags(), orEmpty(req.conditionTags()));
    }

    /**
     * 按病区配置班次判定当前班次 code（无配置行/未命中返回 null）。
     * 当班判定为展示辅助语义，不作任何权威边界（班次权威在交接班 Task 9）。
     */
    private String currentShiftCode(String wardId) {
        NursingWardConfig config = wardConfigMapper.selectOne(
                Wrappers.<NursingWardConfig>lambdaQuery().eq(NursingWardConfig::getWardId, wardId));
        if (config == null) {
            return null;
        }
        LocalTime now = LocalTime.now();
        String hit = null;
        for (ShiftDefinition shift : parseShifts(config, wardId)) {
            if (matchesShiftWindow(shift, now)) {
                hit = shift.code();
                break;
            }
        }
        return hit;
    }

    /**
     * 班次时间窗判定：start &lt; end 为同日窗（含头不含尾）；start &gt;= end 为跨零点环绕窗；
     * 「24:00」为种子班次约定结束时刻（如 EVENING 16:00-24:00），语义取当日最大时刻。
     *
     * @param shift 班次定义，非空
     * @param now   判定时钟，非空
     * @return true=now 落在该班次窗口内
     * @throws IllegalStateException 班次时刻文本解析失败（服务端配置数据异常）
     */
    private boolean matchesShiftWindow(ShiftDefinition shift, LocalTime now) {
        try {
            LocalTime start = LocalTime.parse(shift.start());
            LocalTime end = "24:00".equals(shift.end()) ? LocalTime.MAX : LocalTime.parse(shift.end());
            if (start.isBefore(end)) {
                return !now.isBefore(start) && now.isBefore(end);
            }
            return !now.isBefore(start) || now.isBefore(end);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("病区配置班次时刻解析失败：shift=" + shift.code(), e);
        }
    }

    /** 配置行定位（未命中定性未知病区 NS-1016）。 */
    private NursingWardConfig requireConfig(String wardId) {
        NursingWardConfig config = wardConfigMapper.selectOne(
                Wrappers.<NursingWardConfig>lambdaQuery().eq(NursingWardConfig::getWardId, wardId));
        if (config == null) {
            throw new BizException(NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "未知病区或缺少护理配置：wardId=" + wardId);
        }
        return config;
    }

    /** 体征频次 JSONB → Map（键=NursingLevel code，值=分钟/次；损坏定性服务端数据异常）。 */
    private Map<String, Integer> parseVitalFreq(NursingWardConfig config, String wardId) {
        try {
            return objectMapper.readValue(
                    config.getVitalFreqConfig(), new TypeReference<LinkedHashMap<String, Integer>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("病区配置体征频次解析失败：wardId=" + wardId, e);
        }
    }

    /** 班次定义 JSONB → 结构化清单（损坏定性服务端数据异常）。 */
    private List<ShiftDefinition> parseShifts(NursingWardConfig config, String wardId) {
        try {
            return objectMapper.readValue(config.getShiftDefinitions(), new TypeReference<List<ShiftDefinition>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("病区配置班次定义解析失败：wardId=" + wardId, e);
        }
    }

    /** 操作者取值（免登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }

    /** 空串归一（DDL NOT NULL 列的入参缺省承载）。 */
    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 非空判定（类型一致性校验用）。 */
    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
