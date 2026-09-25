package com.fuyun.inpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.VisitAdmittedPayload;
import com.fuyun.inpatient.api.payload.VisitRegisteredPayload;
import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.AdmissionCreateRequest;
import com.fuyun.inpatient.dto.AdmissionScheduleRequest;
import com.fuyun.inpatient.dto.VisitRegisterRequest;
import com.fuyun.inpatient.dto.WardAdmitRequest;
import com.fuyun.inpatient.entity.Admission;
import com.fuyun.inpatient.entity.Bed;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.enums.AdmissionStatus;
import com.fuyun.inpatient.enums.AdmissionType;
import com.fuyun.inpatient.enums.BedStatus;
import com.fuyun.inpatient.enums.SourceType;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.AdmissionMapper;
import com.fuyun.inpatient.mapper.BedMapper;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.properties.InpatientProperties;
import com.fuyun.inpatient.service.AdmissionService;
import com.fuyun.inpatient.service.BedService;
import com.fuyun.inpatient.vo.AdmissionVO;
import com.fuyun.inpatient.vo.ArrearsAlarmVO;
import com.fuyun.inpatient.vo.InpatientVisitVO;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import com.fuyun.patient.api.PatientDisplayName;
import com.fuyun.patient.api.PatientNameQuery;
import com.fuyun.patient.api.VisitIdValidator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 入院登记域服务实现（V902 两表业务面）。守卫链（住院证登记）：来源/类型词表校验（IP-1022，
 * Web 层 @Pattern 兜底）→ 患者归一/拦截（FROZEN 拒 IP-1003；MERGED 收敛主档，CF-3）→ 发 AD 号
 * 落库（uk_admission_no 兜底转 IP-1023）。登记确认红线方法单事务全链：二次解析 → admission
 * CAS 置 COMPLETED → nextVisitId 签发 → 结构自检（失败 IllegalStateException 回滚）→ visit
 * 落库 REGISTERED → 事务内发布 VisitRegisteredPayload（AFTER_COMMIT 出 MQ）。
 * 状态迁移一律 @Update CAS + 影响行数判定（GC26，显式 deleted=0）；床位联动三处（Task 4
 * 补齐）：schedule 预约目标床位置 RESERVED、cancel 宽容联动释放（作废 CAS 命中后回读
 * 权威 target_bed_id 与床行实态，仅 RESERVED 才释放，非预占 warn 留痕放行作废）、admitWard
 * 入科床位 RESERVED→OCCUPIED + bed_assign 开流水——联动失败整体事务回滚（BedService 同源
 * CAS 权威）。
 * Task 10 追加住院计费入口欠费面（FU-M04-08）：押金变动回执驱动的欠费标识本地裁决与 CAS
 * 刷新（InpatientProperties.depositFloorFen 阈值全局一份）+ 病区欠费清单聚合（患者摘要经
 * PatientNameQuery 脱敏出网——GC22；金额零出网——GC18）。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class AdmissionServiceImpl extends ServiceImpl<AdmissionMapper, Admission> implements AdmissionService {

    /** 无登录上下文场景的操作者回退值（与 V902 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 队列排序权重 SQL：急诊优先 ＞ 预约时段（expect_date 升序、空值排后）＞ 候床时长（建行时间升序）；常量拼接无入参，SQL 注入面为零 */
    private static final String QUEUE_ORDER_BY =
            "ORDER BY CASE WHEN admission_type = 'EMERGENCY' THEN 0 ELSE 1 END, expect_date ASC NULLS LAST, created_at ASC";

    /** 护理级别词表（V902 nursing_level 值域；权威在本域，M05 为视图镜像） */
    private static final Set<String> NURSING_LEVELS = Set.of("SPECIAL", "CRITICAL", "NORMAL");

    /** 在院状态全集（欠费清单聚合限定——已出院/已作废不进护士站清单；与 OngoingVisitQuery 在院语义同源） */
    private static final List<String> IN_WARD_STATUSES = List.of(
            VisitStatus.REGISTERED.getCode(),
            VisitStatus.ADMITTED.getCode(),
            VisitStatus.DISCHARGE_REQUESTED.getCode());

    private final InpatientVisitMapper visitMapper;

    private final BedMapper bedMapper;

    private final InpatientSeqGate seqGate;

    private final PatientContextResolver patientContextResolver;

    private final PatientNameQuery patientNameQuery;

    private final InpatientProperties properties;

    private final BedService bedService;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 InpatientWebConfig @Import）。
     *
     * @param admissionMapper        住院证 mapper，非空；ServiceImpl 基座 mapper
     * @param visitMapper            住院就诊 mapper，非空；登记确认落库、入科确认与欠费标识 CAS
     * @param bedMapper              床位 mapper，非空；欠费清单床位号批量映射
     * @param seqGate                住院业务号发号器（AD 号 / I 型 visit_id），非空
     * @param patientContextResolver 患者上下文解析（patient api），非空；归一/拦截
     * @param patientNameQuery       患者脱敏展示名查询（patient api），非空；欠费清单患者摘要
     *                               出网面（姓名原文不出 patient 模块，GC22）
     * @param properties             住院域参数（押金下限阈值——欠费标识判定基准），非空
     * @param bedService             床位管理服务（床位联动三处：预占/释放/占床），非空
     * @param events                 进程内事件发布器（InpatientEventPublisher AFTER_COMMIT 出 MQ），非空
     */
    public AdmissionServiceImpl(
            AdmissionMapper admissionMapper,
            InpatientVisitMapper visitMapper,
            BedMapper bedMapper,
            InpatientSeqGate seqGate,
            PatientContextResolver patientContextResolver,
            PatientNameQuery patientNameQuery,
            InpatientProperties properties,
            BedService bedService,
            ApplicationEventPublisher events) {
        this.visitMapper = visitMapper;
        this.bedMapper = bedMapper;
        this.seqGate = seqGate;
        this.patientContextResolver = patientContextResolver;
        this.patientNameQuery = patientNameQuery;
        this.properties = properties;
        this.bedService = bedService;
        this.events = events;
    }

    /**
     * 住院证登记（登记即建单入 WAITING 候床队列）：守卫链见类注。
     *
     * @param req 登记入参，非空；来源：医生站/登记台开证
     * @return 建单出参（status=WAITING），非空
     * @throws BizException IP-1022/IP-1003/IP-1023/PAT-1001（接口注全清单）
     */
    @Override
    @Transactional
    public AdmissionVO create(AdmissionCreateRequest req) {
        // 守卫链①：来源/入院类型词表校验（服务面校验覆盖模块内直调场景，Web 层由 @Pattern 兜底）
        SourceType sourceType = SourceType.fromCode(req.sourceType());
        if (sourceType == null) {
            throw paramInvalid("sourceType", req.sourceType());
        }
        AdmissionType admissionType = AdmissionType.fromCode(req.admissionType());
        if (admissionType == null) {
            throw paramInvalid("admissionType", req.admissionType());
        }
        // 守卫链①′：门诊转诊来源必须携门诊 visit_id 引用（M03 转诊关联，两 visit 各自独立）
        if (sourceType == SourceType.OUTPATIENT
                && (req.sourceVisitId() == null || req.sourceVisitId().isBlank())) {
            throw new BizException(
                    InpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "门诊转诊来源必须携带 sourceVisitId：patientId=" + req.patientId());
        }
        // 守卫链②：档案归一/拦截（FROZEN 拒新就诊；MERGED 按 resolvedPatientId 收敛主档，CF-3）
        PatientContextView ctx = patientContextResolver.resolve(req.patientId());
        if (ctx.blocked()) {
            log.warn("住院证登记被拒（档案冻结）：patientId={}，原因={}", req.patientId(), ctx.blockReason());
            throw new BizException(
                    InpatientErrorCode.PATIENT_BLOCKED,
                    HttpStatus.CONFLICT,
                    "患者档案已冻结，禁止办理入院：patientId=" + req.patientId() + "，原因：" + ctx.blockReason());
        }
        // 发号：AD 住院证号（InpatientSeqGate 唯一取号出口）
        String admissionNo = seqGate.nextNo("AD");
        Admission row = new Admission();
        row.setAdmissionNo(admissionNo);
        row.setPatientId(ctx.resolvedPatientId());
        row.setSourceType(sourceType.getCode());
        row.setSourceVisitId(req.sourceVisitId());
        row.setTargetDeptId(req.targetDeptId());
        row.setTargetWardId(req.targetWardId());
        row.setAdmissionType(admissionType.getCode());
        row.setExpectDate(req.expectDate());
        row.setDiagnosisSummary(req.diagnosisSummary());
        row.setIssuedDoctorId(req.issuedDoctorId());
        row.setStatus(AdmissionStatus.WAITING.getCode());
        String operator = operator();
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        try {
            // 数据库写操作：住院证落库（uk_admission_no 部分唯一索引兜底防双写）
            baseMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException(InpatientErrorCode.CONFLICT, HttpStatus.CONFLICT, "住院证号唯一冲突（幂等拒绝）：" + admissionNo);
        }
        log.info(
                "住院证登记入队：admissionNo={}，patientId={}，sourceType={}，admissionType={}，expectDate={}，operator={}",
                admissionNo,
                row.getPatientId(),
                sourceType.getCode(),
                admissionType.getCode(),
                req.expectDate(),
                operator);
        return AdmissionVO.from(row);
    }

    /**
     * 候床队列分页查询：排序权重见 QUEUE_ORDER_BY（急诊优先＞预约时段＞候床时长）。
     *
     * @param status 状态过滤（可空=全部状态），可空；来源：查询参数
     * @param page   页码（0 基），非负
     * @param size   单页条数，正
     * @return 分页出参，非空
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<AdmissionVO> queue(AdmissionStatus status, int page, int size) {
        // 状态条件缺席即全状态（null.getCode() 惰性求值防护：先取值再进条件）
        String statusCode = status == null ? null : status.getCode();
        // 数据库读操作：队列分页（0 基请求转 MP 1 基 current；Wrappers 直构避免链式查询对
        // mapper 代理的反射依赖，WardMetaServiceImpl 同款形态）
        Page<Admission> result = baseMapper.selectPage(
                new Page<>(page + 1, size),
                Wrappers.<Admission>lambdaQuery()
                        .eq(statusCode != null, Admission::getStatus, statusCode)
                        .last(QUEUE_ORDER_BY));
        return PageResult.of(
                result.getRecords().stream().map(AdmissionVO::from).toList(), page, size, result.getTotal());
    }

    /**
     * 预约入院/预住院（WAITING→SCHEDULED）：目标床位/预约日期 CAS 同语句落值；携目标床位时
     * 同事务联动床位预占（Task 4 联动①——BedService.reserveForAdmission 置 RESERVED，预占
     * 失败整体预约事务回滚：预约到不可用床必须整体失败；预住院模式无床不联动）。
     *
     * @param admissionNo 住院证号，非空；来源：路径参数
     * @param req         预约入参，非空；来源：登记台签床调度
     * @return 预约后出参（status=SCHEDULED），非空
     * @throws BizException IP-1001/IP-1002/IP-1004/IP-1005/IP-1006（接口注全清单）
     */
    @Override
    @Transactional
    public AdmissionVO schedule(String admissionNo, AdmissionScheduleRequest req) {
        Admission admission = requireByNo(admissionNo);
        String operator = operator();
        // 数据库写操作：预约 CAS（WAITING→SCHEDULED，目标面同语句落值；0 行定性状态机违例）
        int rows =
                baseMapper.casSchedule(admissionNo, req.targetWardId(), req.targetBedId(), req.expectDate(), operator);
        if (rows == 0) {
            throw stateRejected(admissionNo, admission.getStatus(), "schedule");
        }
        // 床位联动①：目标床位置 RESERVED（同事务——预占失败整体回滚；无床预住院不联动）
        if (req.targetBedId() != null) {
            bedService.reserveForAdmission(req.targetBedId());
        }
        // 回显载体：以入参目标面 + 迁移后状态构造出参（零回读）
        admission.setStatus(AdmissionStatus.SCHEDULED.getCode());
        admission.setTargetWardId(req.targetWardId());
        admission.setTargetBedId(req.targetBedId());
        admission.setExpectDate(req.expectDate());
        log.info(
                "预约入院完成：admissionNo={}，targetWardId={}，targetBedId={}，expectDate={}，operator={}",
                admissionNo,
                req.targetWardId(),
                req.targetBedId(),
                req.expectDate(),
                operator);
        return AdmissionVO.from(admission);
    }

    /**
     * 住院证作废（WAITING/SCHEDULED→CANCELLED，终态）；床位联动②取<b>宽容语义</b>——作废
     * CAS 命中后回读住院证行权威 target_bed_id（并发预约窗口下 CAS 前快照可滞后），再回读
     * 床行实态：仅 RESERVED 才同事务联动释放（Task 4 联动②——BedService.releaseForAdmission
     * 置 FREE）；非预占态（预占床已被登记台手工释放为 FREE/流转其他态/床位缺失）warn 留痕
     * 后放行作废——预占缺失不得阻断住院证终态落定。
     *
     * @param admissionNo 住院证号，非空；来源：路径参数
     * @return 作废后出参（status=CANCELLED），非空
     * @throws BizException IP-1001/IP-1002/IP-1005（接口注全清单；IP-1005 仅释放瞬间床位被
     *                       并发流转的窗口场景，重试作废即自愈）
     */
    @Override
    @Transactional
    public AdmissionVO cancel(String admissionNo) {
        Admission admission = requireByNo(admissionNo);
        String operator = operator();
        // 数据库写操作：作废 CAS（候床/预约两态可作废；0 行定性终态违例）
        if (baseMapper.casCancel(admissionNo, operator) == 0) {
            throw stateRejected(admissionNo, admission.getStatus(), "cancel");
        }
        String priorStatus = admission.getStatus();
        // 回读作废行取权威 target_bed_id（释放决策不依赖 CAS 前快照：WAITING 快照读取后并发
        // schedule 已落 SCHEDULED+预占床位的窗口，快照 target_bed_id 仍为空——以回读实态为准）
        Admission cancelled = requireByNo(admissionNo);
        // 床位联动②（宽容语义）：仅床实态 RESERVED 才 CAS 释放；非预占（登记台已手工释放/
        // 流转其他态/床位缺失）warn 留痕放行作废——预占缺失不得阻断住院证终态落定
        Long targetBedId = cancelled.getTargetBedId();
        if (targetBedId != null) {
            String bedStatus = bedService.bedStatus(targetBedId);
            if (BedStatus.RESERVED.getCode().equals(bedStatus)) {
                bedService.releaseForAdmission(targetBedId);
            } else {
                log.warn(
                        "住院证作废联动释放跳过（床位非预占态，放行作废）：admissionNo={}，bedId={}，床实态={}", admissionNo, targetBedId, bedStatus);
            }
        }
        cancelled.setStatus(AdmissionStatus.CANCELLED.getCode());
        log.info("住院证作废：admissionNo={}，前置状态={}，operator={}", admissionNo, priorStatus, operator);
        return AdmissionVO.from(cancelled);
    }

    /**
     * 入院登记确认（<b>红线方法</b>，单事务全链）：二次解析 → CAS 置 COMPLETED → 签发 I 型
     * visit_id → 结构自检（失败回滚）→ 就诊落库 REGISTERED → 事务内发布登记事件。
     *
     * @param admissionNo 住院证号，非空；来源：路径参数
     * @param req         登记入参（医保类型），非空；来源：登记台核验医保凭证
     * @return 就诊出参（status=REGISTERED，visitId 已签发），非空
     * @throws BizException IP-1001/IP-1003/IP-1002/IP-1023（接口注全清单）
     * @throws IllegalStateException visit_id 结构自检失败（回滚全事务——红线护栏）
     */
    @Override
    @Transactional
    public InpatientVisitVO register(String admissionNo, VisitRegisterRequest req) {
        Admission admission = requireByNo(admissionNo);
        // 守卫链①：患者可用性二次解析（建单后冻结的场景在此拦截；MERGED 收敛主档与签发时点同步）
        PatientContextView ctx = patientContextResolver.resolve(admission.getPatientId());
        if (ctx.blocked()) {
            log.warn(
                    "入院登记确认被拒（档案冻结）：admissionNo={}，patientId={}，原因={}",
                    admissionNo,
                    admission.getPatientId(),
                    ctx.blockReason());
            throw new BizException(
                    InpatientErrorCode.PATIENT_BLOCKED,
                    HttpStatus.CONFLICT,
                    "患者档案已冻结，禁止办理入院登记：admissionNo=" + admissionNo + "，原因：" + ctx.blockReason());
        }
        String operator = operator();
        // 事务链①：住院证 CAS 置 COMPLETED（0 行=终态证违例；与后续签发/落库同事务成败与共）
        if (baseMapper.casComplete(admissionNo, operator) == 0) {
            throw stateRejected(admissionNo, admission.getStatus(), "register");
        }
        // 事务链②：签发 I 型 14 位 visit_id（本模块唯一签发通道 nextVisitId）
        String visitId = seqGate.nextVisitId();
        // 事务链③：结构自检（发号器异常防线——失败抛 IllegalStateException 回滚全事务，红线护栏）
        if (!VisitIdValidator.isValid(visitId)) {
            throw new IllegalStateException("visit_id 结构自检失败（发号器异常），本次登记回滚：" + visitId);
        }
        InpatientVisit row = new InpatientVisit();
        row.setAdmissionId(admission.getId());
        row.setVisitId(visitId);
        // 签发时点归一主档与 visit_id 同事务同时落库（M02 结论 ④）
        row.setPatientId(ctx.resolvedPatientId());
        row.setInsuranceType(req.insuranceType());
        // 入院诊断自住院证誊写（脱敏红线：诊断文本仅落库回显，禁入事件载荷）
        row.setAdmissionDiagnosis(admission.getDiagnosisSummary());
        row.setRegisteredAt(OffsetDateTime.now());
        row.setArrearsFlag(false);
        row.setStatus(VisitStatus.REGISTERED.getCode());
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        try {
            // 事务链④：就诊落库（uk_visit_id 兜底签发幂等——发号器异常回绕极端并发场景）
            visitMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "visit_id 唯一冲突（发号器异常回绕，本次登记回滚）：visitId=" + visitId);
        }
        // 事务链⑤：登记事件（事务内发布，AFTER_COMMIT 出 fy.topic——M13 医保入院办理依据；
        // 载荷仅 visitId/patientId/admissionNo/registeredAt/insuranceType，禁患者姓名/诊断文本）
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_VISIT_REGISTERED,
                new VisitRegisteredPayload(
                        visitId,
                        row.getPatientId(),
                        admissionNo,
                        row.getRegisteredAt().toInstant(),
                        req.insuranceType())));
        log.info(
                "入院登记确认完成：admissionNo={}，visitId={}，patientId={}，insuranceType={}，operator={}",
                admissionNo,
                visitId,
                row.getPatientId(),
                req.insuranceType(),
                operator);
        return InpatientVisitVO.from(row);
    }

    /**
     * 入科确认（visit REGISTERED→ADMITTED）：科室/病区/床位/护理级别 CAS 同语句落值（入科时点
     * 库端 now()）→ 回读行取入科时点 → 床位联动③（Task 4 补齐——BedService.occupyForAdmission
     * 床位 RESERVED→OCCUPIED CAS + bed_assign 开 ADMISSION 流水，占床失败整体入科事务回滚）→
     * 事务内发布入科事件。
     *
     * @param visitId 住院就诊号，非空；来源：路径参数
     * @param req     入科入参，非空；来源：病区护士站入科单
     * @return 入科后就诊出参（status=ADMITTED），非空
     * @throws BizException IP-1007/IP-1008/IP-1022/IP-1023/IP-1004/IP-1005/IP-1006（接口注全清单）
     */
    @Override
    @Transactional
    public InpatientVisitVO admitWard(String visitId, WardAdmitRequest req) {
        // 守卫链①：护理级别词表校验（服务面校验覆盖模块内直调场景，Web 层由 @Pattern 兜底）
        if (!NURSING_LEVELS.contains(req.nursingLevel())) {
            throw paramInvalid("nursingLevel", req.nursingLevel());
        }
        // 就诊行定位（未命中定性 IP-1007；逻辑删由 @TableLogic 自动过滤）
        InpatientVisit visit = requireByVisitId(visitId);
        if (visit == null) {
            throw new BizException(InpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "住院就诊不存在：" + visitId);
        }
        String operator = operator();
        // 数据库写操作：入科 CAS（REGISTERED→ADMITTED，入科时点库端 now()；0 行定性状态机违例）
        int rows = visitMapper.casAdmitWard(
                visitId,
                req.deptId(),
                req.wardId(),
                req.bedId(),
                req.attendingDoctorId(),
                req.nursingLevel(),
                operator);
        if (rows == 0) {
            throw new BizException(
                    InpatientErrorCode.VISIT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "住院就诊状态不允许入科：visitId=" + visitId + "，当前状态=" + visit.getStatus());
        }
        // 回读入科后行（入科时点由库端 now() 写入，禁应用时钟；回读缺失=并发逻辑删，定性冲突）
        InpatientVisit admitted = requireByVisitId(visitId);
        if (admitted == null) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT, HttpStatus.CONFLICT, "入科确认后就诊行回读缺失（并发逻辑删）：visitId=" + visitId);
        }
        // 床位联动③：床位 RESERVED→OCCUPIED + bed_assign 开 ADMISSION 流水（同事务——占床失败
        // 整体入科回滚；患者主索引取回读行权威值免二次解析）
        bedService.occupyForAdmission(req.bedId(), visitId, admitted.getPatientId());
        // 入科事件（事务内发布，AFTER_COMMIT 出 fy.topic——M05 病区患者视图维护、M14 待绑定提醒；
        // 载荷仅定位键与护理级别，禁患者姓名/诊断文本；床位流转权威归 bed 域，bed.changed 由联动面发布）
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_VISIT_ADMITTED,
                new VisitAdmittedPayload(
                        visitId,
                        admitted.getPatientId(),
                        req.wardId(),
                        req.bedId(),
                        admitted.getAdmittedAt().toInstant(),
                        req.nursingLevel())));
        log.info(
                "入科确认完成：visitId={}，patientId={}，wardId={}，bedId={}，nursingLevel={}，operator={}",
                visitId,
                admitted.getPatientId(),
                req.wardId(),
                req.bedId(),
                req.nursingLevel(),
                operator);
        return InpatientVisitVO.from(admitted);
    }

    /**
     * 押金变动回执消费体（FU-M04-08 住院计费入口）：余额与押金下限阈值本地裁决（M04 阈值
     * 全局一份，担保白名单免提醒归 P3）→ 欠费标识 CAS 刷新（目标值异值限定幂等）。跌破置位
     * 走 warn（护士站欠费标识数据源——五大降级清单②：M01 通知中心缺位，欠费提醒=工作站列表
     * 可见）；回升复位走 info。零行（标识已处目标态的重复投递/就诊行不存在）info 留痕直返。
     *
     * @param visitId    CF-3 住院就诊号（I 型 14 位，载荷原值），非空；来源：billing 事件载荷
     * @param balanceFen 变动后押金余额（分，事件载荷原值；GC18 零金额输入——仅事件消费面）
     */
    @Override
    @Transactional
    public void onDepositChanged(String visitId, long balanceFen) {
        // 阈值判定本地裁决：余额跌破押金下限即欠费（回升至阈值及以上复位——含等值不算欠费）
        boolean arrears = balanceFen < properties.depositFloorFen();
        // 数据库写操作：欠费标识 CAS（目标值异值限定——重复消费/并发投递零行幂等直返；
        // 消费线程无登录上下文，操作者回退 system 与审计列默认同源）
        int rows = visitMapper.casUpdateArrearsFlag(visitId, arrears, operator());
        if (rows == 0) {
            log.info("押金变动回执欠费标识无需变更（幂等直返）：visitId={}，balance={}，目标标识={}", visitId, balanceFen, arrears);
            return;
        }
        if (arrears) {
            // warn：余额跌破下限是护士站需即时可见的异常业务状态（欠费清单数据源）
            log.warn(
                    "住院欠费标识置位（余额跌破押金下限）：visitId={}，balance={}，阈值={}",
                    visitId,
                    balanceFen,
                    properties.depositFloorFen());
            return;
        }
        log.info("押金回升欠费标识复位：visitId={}，balance={}，阈值={}", visitId, balanceFen, properties.depositFloorFen());
    }

    /**
     * 病区欠费清单：arrears_flag=true 的在院就诊聚合（在院三态限定），出参=就诊号+患者脱敏
     * 展示名+床位号+标识时点（updated_at 近似承载）；床位号与脱敏展示名两跳批量取数免行级
     * N+1；按标识时点倒序（最新欠费在前）。患者摘要脱敏红线（GC22）：PatientNameQuery 只出
     * 脱敏展示名，姓名原文不出 patient 模块；金额零出网（GC18——欠费明细归 M13 前端直调面）。
     *
     * @param wardId 病区编码，非空；来源：查询参数（护士站一览）
     * @return 欠费清单行（标识时点倒序；病区无欠费在院患者返回空清单），非空
     */
    @Override
    @Transactional(readOnly = true)
    public List<ArrearsAlarmVO> arrearsList(String wardId) {
        // 数据库读操作：病区欠费在院行聚合（current_ward_id 定位 + 本地欠费标识 + 在院三态限定；
        // REGISTERED 未入科无病区归属，天然不进清单——语义兜底而非过滤依赖）
        List<InpatientVisit> visits = visitMapper.selectList(Wrappers.<InpatientVisit>lambdaQuery()
                .eq(InpatientVisit::getCurrentWardId, wardId)
                .eq(InpatientVisit::getArrearsFlag, true)
                .in(InpatientVisit::getStatus, IN_WARD_STATUSES)
                .orderByDesc(InpatientVisit::getUpdatedAt));
        if (visits.isEmpty()) {
            // 空集直过（病区无欠费在院患者）——零二次取数
            return List.of();
        }
        // 床位号批量映射（current_bed_id 一次取数；床位行缺失兜底 null——转科窗口等数据滞后容错）
        Set<Long> bedIds = visits.stream()
                .map(InpatientVisit::getCurrentBedId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> bedNoById = bedIds.isEmpty()
                ? Map.of()
                : bedMapper.selectList(Wrappers.<Bed>lambdaQuery().in(Bed::getId, bedIds)).stream()
                        .collect(Collectors.toMap(Bed::getId, Bed::getBedNo));
        // 患者脱敏展示名批量（in 精确投影——PatientNameQuery 契约禁 N+1；未命中 id 不在结果中）
        Map<Long, String> nameByPatientId =
                patientNameQuery
                        .displayNamesOf(visits.stream()
                                .map(InpatientVisit::getPatientId)
                                .collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(PatientDisplayName::patientId, PatientDisplayName::displayName));
        return visits.stream()
                .map(visit -> new ArrearsAlarmVO(
                        visit.getVisitId(),
                        nameByPatientId.get(visit.getPatientId()),
                        visit.getCurrentBedId() == null ? null : bedNoById.get(visit.getCurrentBedId()),
                        visit.getUpdatedAt()))
                .toList();
    }

    /** 按住院证号定位行（未命中定性 IP-1001；逻辑删由 @TableLogic 自动过滤）。 */
    private Admission requireByNo(String admissionNo) {
        Admission admission =
                baseMapper.selectOne(Wrappers.<Admission>lambdaQuery().eq(Admission::getAdmissionNo, admissionNo));
        if (admission == null) {
            throw new BizException(
                    InpatientErrorCode.ADMISSION_NOT_FOUND, HttpStatus.NOT_FOUND, "住院证不存在：" + admissionNo);
        }
        return admission;
    }

    /** 按就诊号定位行（未命中返回 null 交调用方定性 IP-1007/IP-1023）。 */
    private InpatientVisit requireByVisitId(String visitId) {
        return visitMapper.selectOne(Wrappers.<InpatientVisit>lambdaQuery().eq(InpatientVisit::getVisitId, visitId));
    }

    /** 入参格式非法（IP-1022）统一构造：词表外/结构校验不过。 */
    private BizException paramInvalid(String field, String value) {
        return new BizException(
                InpatientErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "入参格式非法——" + field + " 词表外：" + value);
    }

    /** 状态机违例（IP-1002）统一构造：0 行 CAS 的调用侧定性。 */
    private BizException stateRejected(String admissionNo, String currentStatus, String action) {
        return new BizException(
                InpatientErrorCode.ADMISSION_STATE_NOT_ALLOWED,
                HttpStatus.CONFLICT,
                "住院证状态不允许该操作：" + action + "，admissionNo=" + admissionNo + "，当前状态=" + currentStatus);
    }

    /** 操作者取值（免登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
