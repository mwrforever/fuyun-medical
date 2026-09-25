package com.fuyun.inpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.BedChangedPayload;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.BedAssignRequest;
import com.fuyun.inpatient.entity.Bed;
import com.fuyun.inpatient.entity.BedAssign;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.enums.AssignType;
import com.fuyun.inpatient.enums.BedStatus;
import com.fuyun.inpatient.enums.TransferType;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.BedAssignMapper;
import com.fuyun.inpatient.mapper.BedMapper;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.service.BedService;
import com.fuyun.inpatient.vo.BedMapVO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 床位管理域服务实现（V903 两表业务面）：五态状态机权威——全部迁移 CAS 条件更新 + 影响行数
 * 判定（GC26，显式 deleted=0），防重复占床硬防线仅 FREE/RESERVED 可占（IP-1006），消毒/
 * 维修中一律拒绝分配/预占（IP-1005）；每次迁移事务内发布 BedChangedPayload（AFTER_COMMIT
 * 出 MQ，V800 id 52——M05 护士站一览与大屏床位动态）。bed_assign 只增流水开账/闭合（未闭合
 * 行每床至多一条，uk_bed_assign_open 兜底转 IP-1023）；床位图聚合零敏感字段。入院登记域
 * 联动（reserveForAdmission/releaseForAdmission/occupyForAdmission）与转科/转床编排床位
 * 流转（transferOut/occupyForTransfer）同源本实现（编排方 @Transactional 传播加入同事务）。
 * 在途三分之「计划」数据面操作归 Task 7/8 计划服务补挂转科钩子（钩子面在 TransferServiceImpl）。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class BedServiceImpl extends ServiceImpl<BedMapper, Bed> implements BedService {

    /** 无登录上下文场景的操作者回退值（与 V903 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 可分配床位的就诊态词表（待入科/在院——已出院/已作废就诊禁再占床） */
    private static final Set<String> ASSIGNABLE_VISIT_STATUSES =
            Set.of(VisitStatus.REGISTERED.getCode(), VisitStatus.ADMITTED.getCode());

    private final BedAssignMapper assignMapper;

    private final InpatientVisitMapper visitMapper;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 InpatientWebConfig @Import）。
     *
     * @param assignMapper 床位占用流水 mapper，非空；开账/闭合只增表操作
     * @param visitMapper  住院就诊 mapper，非空；占床主体解析与床位图摘要聚合
     * @param events       进程内事件发布器（InpatientEventPublisher AFTER_COMMIT 出 MQ），非空
     */
    public BedServiceImpl(
            BedAssignMapper assignMapper, InpatientVisitMapper visitMapper, ApplicationEventPublisher events) {
        this.assignMapper = assignMapper;
        this.visitMapper = visitMapper;
        this.events = events;
    }

    /**
     * 病区床位图聚合：床位行（床号升序）+ 占用就诊批量解析（一次 in 查询，空清单短路——MP
     * in() 空集合生成非法 SQL）；占用行缺就诊时摘要降级为空不阻断床位图（warn 留痕）。
     *
     * @param wardId 病区编码，非空；来源：查询参数
     * @return 床位图行清单（病区无床返回空清单），非空
     */
    @Override
    @Transactional(readOnly = true)
    public List<BedMapVO> bedMap(String wardId) {
        // 数据库读操作：病区床位全量（床号升序——床位图排序键）
        List<Bed> beds = baseMapper.selectList(
                Wrappers.<Bed>lambdaQuery().eq(Bed::getWardId, wardId).orderByAsc(Bed::getBedNo));
        if (beds.isEmpty()) {
            return List.of();
        }
        // 占用床位收集（空清单短路：禁以空集合触达 in 查询）
        List<String> occupiedVisitIds = beds.stream()
                .filter(bed -> BedStatus.OCCUPIED.getCode().equals(bed.getStatus()))
                .map(Bed::getVisitId)
                .filter(Objects::nonNull)
                .toList();
        if (occupiedVisitIds.isEmpty()) {
            return beds.stream().map(bed -> BedMapVO.from(bed, null)).toList();
        }
        // 数据库读操作：占用就诊批量解析（visit_id 唯一索引承载 in 查询）
        Map<String, InpatientVisit> visits = visitMapper
                .selectList(Wrappers.<InpatientVisit>lambdaQuery().in(InpatientVisit::getVisitId, occupiedVisitIds))
                .stream()
                .collect(Collectors.toMap(InpatientVisit::getVisitId, Function.identity(), (left, right) -> left));
        return beds.stream()
                .map(bed -> BedMapVO.from(bed, resolveOccupiedVisit(bed, visits)))
                .toList();
    }

    /**
     * 床位预占（FREE→RESERVED）：守卫链=存在性（IP-1004）→消毒/维修中拒绝（IP-1005）→
     * 非空床拒绝（IP-1006）→ CAS（0 行=并发窗口同判 IP-1006）；成功广播 bed.changed（无主体）。
     *
     * @param bedId 床位 id，非空；来源：路径参数/预约入参 targetBedId
     */
    @Override
    @Transactional
    public void reserve(Long bedId) {
        Bed bed = requireBed(bedId);
        rejectUnavailable(bed, "预占");
        // 已预占/已占用：床位已被占用（预占与占床共用占用语义防线）
        if (!BedStatus.FREE.getCode().equals(bed.getStatus())) {
            throw occupied(bed);
        }
        if (baseMapper.casReserve(bedId) == 0) {
            // 前置校验与 CAS 之间的并发预占窗口：同判 IP-1006
            throw occupied(bed);
        }
        broadcast(bed, BedStatus.RESERVED, null);
        log.info("床位预占：bedId={}，bedNo={}，wardId={}，operator={}", bedId, bed.getBedNo(), bed.getWardId(), operator());
    }

    /**
     * 床位占床（直接分配快速通道）：占用主体解析（IP-1007/IP-1008）后走同源占床 CAS + 开账
     * + 广播（assign_type=ADMISSION）。
     *
     * @param bedId 床位 id，非空；来源：路径参数
     * @param req   占床入参（占用主体就诊号），非空；来源：护士站分配床位
     */
    @Override
    @Transactional
    public void assign(Long bedId, BedAssignRequest req) {
        // 占用主体资格先行：就诊在位且待入科/在院（已出院床位禁再分配）
        InpatientVisit visit = visitMapper.selectOne(
                Wrappers.<InpatientVisit>lambdaQuery().eq(InpatientVisit::getVisitId, req.visitId()));
        if (visit == null) {
            throw new BizException(
                    InpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "住院就诊不存在：" + req.visitId());
        }
        if (!ASSIGNABLE_VISIT_STATUSES.contains(visit.getStatus())) {
            throw new BizException(
                    InpatientErrorCode.VISIT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "住院就诊状态不允许分配床位：visitId=" + req.visitId() + "，当前状态=" + visit.getStatus());
        }
        occupy(bedId, req.visitId(), visit.getPatientId(), AssignType.ADMISSION);
    }

    /**
     * 释放预占（RESERVED→FREE）：占用态禁手工释放（须走转床/转科/出院编排流转消毒）。
     *
     * @param bedId 床位 id，非空；来源：路径参数/住院证行 target_bed_id
     */
    @Override
    @Transactional
    public void release(Long bedId) {
        Bed bed = requireBed(bedId);
        if (!BedStatus.RESERVED.getCode().equals(bed.getStatus())) {
            throw stateRejected(bed, "释放");
        }
        if (baseMapper.casRelease(bedId) == 0) {
            // 并发释放窗口（前置校验后状态迁移）：同判状态机违例
            throw stateRejected(bed, "释放");
        }
        broadcast(bed, BedStatus.FREE, null);
        log.info("释放床位预占：bedId={}，bedNo={}，wardId={}，operator={}", bedId, bed.getBedNo(), bed.getWardId(), operator());
    }

    /**
     * 消毒完成确认（DISINFECTING→FREE）：终末消毒完成回可分配池。
     *
     * @param bedId 床位 id，非空；来源：路径参数
     */
    @Override
    @Transactional
    public void disinfectDone(Long bedId) {
        Bed bed = requireBed(bedId);
        if (!BedStatus.DISINFECTING.getCode().equals(bed.getStatus())) {
            throw stateRejected(bed, "消毒完成确认");
        }
        if (baseMapper.casDisinfectDone(bedId) == 0) {
            throw stateRejected(bed, "消毒完成确认");
        }
        broadcast(bed, BedStatus.FREE, null);
        log.info("消毒完成回池：bedId={}，bedNo={}，wardId={}，operator={}", bedId, bed.getBedNo(), bed.getWardId(), operator());
    }

    /**
     * 床位转维修（FREE→MAINTENANCE）：占用/预占/消毒中床位禁转维修。
     *
     * @param bedId 床位 id，非空；来源：路径参数
     */
    @Override
    @Transactional
    public void maintain(Long bedId) {
        Bed bed = requireBed(bedId);
        if (!BedStatus.FREE.getCode().equals(bed.getStatus())) {
            throw stateRejected(bed, "转维修");
        }
        if (baseMapper.casMaintain(bedId) == 0) {
            throw stateRejected(bed, "转维修");
        }
        broadcast(bed, BedStatus.MAINTENANCE, null);
        log.info("床位转维修：bedId={}，bedNo={}，wardId={}，operator={}", bedId, bed.getBedNo(), bed.getWardId(), operator());
    }

    /**
     * 维修恢复（MAINTENANCE→FREE）。
     *
     * @param bedId 床位 id，非空；来源：路径参数
     */
    @Override
    @Transactional
    public void maintainDone(Long bedId) {
        Bed bed = requireBed(bedId);
        if (!BedStatus.MAINTENANCE.getCode().equals(bed.getStatus())) {
            throw stateRejected(bed, "维修恢复");
        }
        if (baseMapper.casMaintainDone(bedId) == 0) {
            throw stateRejected(bed, "维修恢复");
        }
        broadcast(bed, BedStatus.FREE, null);
        log.info("维修恢复回池：bedId={}，bedNo={}，wardId={}，operator={}", bedId, bed.getBedNo(), bed.getWardId(), operator());
    }

    /**
     * 预约入院床位预占联动（schedule 面）：同源 reserve 路径——预占失败整体预约事务回滚。
     *
     * @param bedId 目标床位 id，非空；来源：预约入参 targetBedId
     */
    @Override
    @Transactional
    public void reserveForAdmission(Long bedId) {
        reserve(bedId);
    }

    /**
     * 预约作废床位释放联动（cancel 面）：同源 release 路径。
     *
     * @param bedId 预占床位 id，非空；来源：住院证行 target_bed_id
     */
    @Override
    @Transactional
    public void releaseForAdmission(Long bedId) {
        release(bedId);
    }

    /**
     * 入科确认占床联动（admit-ward 面）：同源占床 CAS + ADMISSION 开账 + 广播——占床失败
     * 整体入科事务回滚。
     *
     * @param bedId     入科床位 id，非空；来源：入科入参 bedId
     * @param visitId   住院就诊号（I 型 14 位），非空
     * @param patientId 占用患者主索引，非空；来源：就诊行（admit-ward 已权威读取）
     */
    @Override
    @Transactional
    public void occupyForAdmission(Long bedId, String visitId, long patientId) {
        occupy(bedId, visitId, patientId, AssignType.ADMISSION);
    }

    /**
     * 转出床流转（编排面）：占用态与占用主体双校验 → OCCUPIED→DISINFECTING CAS → 闭合
     * 未继行流水 → 广播（无主体——患者已随编排转入目标床）。
     *
     * @param bedId   转出床位 id，非空；来源：就诊行 current_bed_id
     * @param visitId 转出主体住院就诊号（I 型 14 位），非空
     */
    @Override
    @Transactional
    public void transferOut(Long bedId, String visitId) {
        Bed bed = requireBed(bedId);
        if (!BedStatus.OCCUPIED.getCode().equals(bed.getStatus())) {
            throw stateRejected(bed, "转出流转");
        }
        // 占用主体双校验：床位被他人占用时禁误流转（bed_id+visit_id 条件在 CAS 二次兜底）
        if (!visitId.equals(bed.getVisitId())) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "床位占用主体不符，禁止流转：bedId=" + bedId + "，期望 visitId=" + visitId + "，实际 visitId=" + bed.getVisitId());
        }
        if (baseMapper.casDisinfect(bedId, visitId) == 0) {
            // 并发流转窗口（占用态/主体在前置校验后迁移）：定性冲突
            throw new BizException(InpatientErrorCode.CONFLICT, HttpStatus.CONFLICT, "转出床并发流转冲突，禁止重复流转：bedId=" + bedId);
        }
        // 只增流水闭合：ended_at 落值（未闭合行唯一索引保证至多一条）
        OffsetDateTime endedAt = OffsetDateTime.now();
        if (assignMapper.closeOpen(bedId, visitId, endedAt) == 0) {
            // 无未闭合流水=开账缺失（数据不一致），抛冲突回滚编排事务
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "转出床位无未闭合占用流水（数据不一致），编排回滚：bedId=" + bedId + "，visitId=" + visitId);
        }
        broadcast(bed, BedStatus.DISINFECTING, null);
        log.info(
                "转出床消毒流转：bedId={}，bedNo={}，wardId={}，visitId={}，endedAt={}，operator={}",
                bedId,
                bed.getBedNo(),
                bed.getWardId(),
                visitId,
                endedAt,
                operator());
    }

    /**
     * 目标床占床（编排面）：同源占床 CAS + 按编排类型开流水（BED_CHANGE/WARD_TRANSFER）+ 广播。
     *
     * @param bedId     目标床位 id，非空；来源：编排入参
     * @param visitId   住院就诊号（I 型 14 位），非空
     * @param patientId 转移患者主索引，非空
     * @param type      编排类型（决定流水 assign_type），非空
     */
    @Override
    @Transactional
    public void occupyForTransfer(Long bedId, String visitId, long patientId, TransferType type) {
        occupy(
                bedId,
                visitId,
                patientId,
                type == TransferType.WARD_TRANSFER ? AssignType.WARD_TRANSFER : AssignType.BED_CHANGE);
    }

    /** 占床统一路径（六端点 assign / 入科联动 / 编排占床同源）：CAS 防重 → 开账 → 广播。 */
    private void occupy(Long bedId, String visitId, long patientId, AssignType type) {
        Bed bed = requireBed(bedId);
        rejectUnavailable(bed, "占床");
        // 防重复占床硬防线：仅 FREE/RESERVED 可占（占用态在此前置拒绝，CAS 兜底并发窗口）
        if (!BedStatus.FREE.getCode().equals(bed.getStatus())
                && !BedStatus.RESERVED.getCode().equals(bed.getStatus())) {
            throw occupied(bed);
        }
        if (baseMapper.casOccupy(bedId, visitId) == 0) {
            // 并发占床窗口：同判 IP-1006（硬防线兜底）
            throw occupied(bed);
        }
        openAssign(bed, visitId, type);
        broadcast(bed, BedStatus.OCCUPIED, patientId);
        log.info(
                "床位占用开账：bedId={}，bedNo={}，wardId={}，visitId={}，assignType={}，operator={}",
                bedId,
                bed.getBedNo(),
                bed.getWardId(),
                visitId,
                type.getCode(),
                operator());
    }

    /** 占用流水开账（只增表 insert；uk_bed_assign_open 兜底并发开账转 IP-1023）。 */
    private void openAssign(Bed bed, String visitId, AssignType type) {
        BedAssign row = new BedAssign();
        row.setBedId(bed.getId());
        row.setVisitId(visitId);
        row.setAssignType(type.getCode());
        row.setStartedAt(OffsetDateTime.now());
        String operator = operator();
        row.setOperator(operator);
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        try {
            // 数据库写操作：占用流水开账（每床未闭合行唯一索引兜底）
            assignMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "床位占用流水未闭合行唯一冲突（并发开账，本次操作回滚）：bedId=" + bed.getId() + "，visitId=" + visitId);
        }
    }

    /** 床位定位（未命中定性 IP-1004；逻辑删由 @TableLogic 自动过滤）。 */
    private Bed requireBed(Long bedId) {
        Bed bed = baseMapper.selectById(bedId);
        if (bed == null) {
            throw new BizException(InpatientErrorCode.BED_NOT_FOUND, HttpStatus.NOT_FOUND, "床位不存在：" + bedId);
        }
        return bed;
    }

    /** 消毒/维修中拒绝（IP-1005）：不可分配态统一前置拦截。 */
    private void rejectUnavailable(Bed bed, String action) {
        String status = bed.getStatus();
        if (BedStatus.DISINFECTING.getCode().equals(status)
                || BedStatus.MAINTENANCE.getCode().equals(status)) {
            throw stateRejected(bed, action);
        }
    }

    /** 状态机违例（IP-1005）统一构造：前置校验与 CAS 0 行共用。 */
    private BizException stateRejected(Bed bed, String action) {
        return new BizException(
                InpatientErrorCode.BED_STATE_NOT_ALLOWED,
                HttpStatus.CONFLICT,
                "床位状态不允许该操作：" + action + "，bedId=" + bed.getId() + "，当前状态=" + bed.getStatus());
    }

    /** 床位已被占用（IP-1006）统一构造：防重复占床硬防线前置拒绝与 CAS 0 行并发窗口共用。 */
    private BizException occupied(Bed bed) {
        return new BizException(
                InpatientErrorCode.BED_OCCUPIED,
                HttpStatus.CONFLICT,
                "床位已被占用：bedId=" + bed.getId() + "，当前状态=" + bed.getStatus());
    }

    /** 占用摘要解析：非占用态不出摘要；占用态缺就诊行（数据不一致）降级为空并 warn 留痕。 */
    private InpatientVisit resolveOccupiedVisit(Bed bed, Map<String, InpatientVisit> visits) {
        if (!BedStatus.OCCUPIED.getCode().equals(bed.getStatus())) {
            return null;
        }
        InpatientVisit visit = bed.getVisitId() == null ? null : visits.get(bed.getVisitId());
        if (visit == null) {
            log.warn("占用床位缺就诊行（床位图摘要降级为空）：bedId={}，visitId={}", bed.getId(), bed.getVisitId());
        }
        return visit;
    }

    /** bed.changed 事务内发布（AFTER_COMMIT 出 MQ；载荷仅定位键与状态，脱敏红线）。 */
    private void broadcast(Bed bed, BedStatus newStatus, Long patientId) {
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_BED_CHANGED,
                new BedChangedPayload(bed.getWardId(), bed.getId(), bed.getBedNo(), newStatus.getCode(), patientId)));
    }

    /** 操作者取值（免登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
