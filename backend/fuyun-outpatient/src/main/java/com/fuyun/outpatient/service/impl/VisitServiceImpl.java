package com.fuyun.outpatient.service.impl;

import static com.fuyun.outpatient.service.OutpatientVisitStateMachine.require;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.api.VisitFinishedPayload;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.dto.FinishVisitRequest;
import com.fuyun.outpatient.entity.ClinicOrder;
import com.fuyun.outpatient.entity.QueueTicket;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.entity.VisitStatusLog;
import com.fuyun.outpatient.enums.OrderStatus;
import com.fuyun.outpatient.enums.TicketStatus;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.internal.OutpatientDomainEvent;
import com.fuyun.outpatient.mapper.ClinicOrderMapper;
import com.fuyun.outpatient.mapper.QueueTicketMapper;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.outpatient.mapper.VisitStatusLogMapper;
import com.fuyun.outpatient.service.ITriageService;
import com.fuyun.outpatient.service.IVisitService;
import com.fuyun.outpatient.vo.DoctorQueueItemVO;
import com.fuyun.outpatient.vo.VisitVO;
import com.fuyun.patient.api.PatientDisplayName;
import com.fuyun.patient.api.PatientNameQuery;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 门诊医生站就诊服务实现（M03 FU-M03-05，Task 8 写路径唯一入口）：接诊（分诊域票 CALLED→SERVING
 * 联动归 ITriageService.markServing+visit WAITING→IN_CONSULT 状态机迁移+admitted_at 国标回填）、
 * 诊毕（离院去向词表 OP-1018→在途单据显式确认校验 OP-1016→visit 状态机校验迁 FINISHED（
 * IN_CONSULT 与显式确认的 PENDING_FEE 均合法）+finished_at/finish_operator/disposition 回填+
 * visit.finished 发布）、医生站候诊列表（本队列 WAITING/CALLED 票+脱敏摘要+过敏声明位）。
 * 接诊/诊毕时间回填走 VisitMapper.casAdmit/casFinish 专用 CAS（状态迁移与国标时间单条 UPDATE，
 * 库端 now() 与票面 serve_time 同源，禁应用服务器时钟防多实例漂移）。visit 全部迁移经
 * OutpatientVisitStateMachine 单点校验+visit_status_log 每迁必记（红线 5）；终态后一切后续动作
 * 被状态机拒绝（OP-1011）。线程安全：无状态单例。装配归 OutpatientWebConfig @Import；
 * com.fuyun.outpatient.service.impl 包 = JaCoCo PACKAGE LINE 1.00 覆盖对象。
 */
@Slf4j
public class VisitServiceImpl implements IVisitService {

    /** 离院去向词表（V705 outpatient.disposition item_code 全集硬编码静态清单——八类含其他，词表外 OP-1018） */
    private static final Set<String> DISPOSITION_CODES = Set.of(
            "DISCHARGE_HOME",
            "TRANSFER_HOSPITAL",
            "TRANSFER_COMMUNITY",
            "NON_MEDICAL_LEAVE",
            "DEATH",
            "OBSERVATION",
            "TRANSFER_INPATIENT",
            "OTHER");

    /** 候诊列表票据状态词表（WAITING 候诊+CALLED 已叫——SERVING 起不在候诊面） */
    private static final List<TicketStatus> QUEUE_VIEW_STATUSES = List.of(TicketStatus.WAITING, TicketStatus.CALLED);

    /** 过敏标识位声明值（P1 恒 false——M02 过敏订阅缓存随 P-later 接入，声明位先挂） */
    private static final boolean ALLERGY_FLAG_P1 = false;

    private final VisitMapper visitMapper;

    private final VisitStatusLogMapper visitStatusLogMapper;

    private final QueueTicketMapper queueTicketMapper;

    private final ClinicOrderMapper clinicOrderMapper;

    private final ITriageService triageService;

    private final PatientNameQuery patientNameQuery;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import，backend 宪法 B.1）。
     *
     * @param visitMapper           就诊记录 mapper，非空；接诊/诊毕 CAS 与时间回填
     * @param visitStatusLogMapper  迁移日志 mapper，非空；红线 5 每迁必记
     * @param queueTicketMapper     候诊票据 mapper，非空；候诊列表权威行读取
     * @param clinicOrderMapper     申请单 mapper，非空；诊毕在途单据校验（CREATED/PENDING_FEE 计数）
     * @param triageService         分诊台服务，非空；接诊票 CALLED→SERVING 联动（分诊域写路径归属）
     * @param patientNameQuery      患者脱敏展示名查询（patient api 契约），非空；候诊列表姓名掩码
     * @param events                Spring 应用事件发布器，非空；事务内发布 visit.finished
     */
    public VisitServiceImpl(
            VisitMapper visitMapper,
            VisitStatusLogMapper visitStatusLogMapper,
            QueueTicketMapper queueTicketMapper,
            ClinicOrderMapper clinicOrderMapper,
            ITriageService triageService,
            PatientNameQuery patientNameQuery,
            ApplicationEventPublisher events) {
        this.visitMapper = visitMapper;
        this.visitStatusLogMapper = visitStatusLogMapper;
        this.queueTicketMapper = queueTicketMapper;
        this.clinicOrderMapper = clinicOrderMapper;
        this.triageService = triageService;
        this.patientNameQuery = patientNameQuery;
        this.events = events;
    }

    /**
     * 接诊（接口 javadoc 契约）：票 CALLED→SERVING+serve_time（分诊域联动）→visit WAITING→
     * IN_CONSULT 状态机迁移+admitted_at 回填+每迁必记，同一事务原子（任一环节失败整单回滚）。
     *
     * @param visitId 就诊号，非空
     * @return 接诊后就诊出参（status=IN_CONSULT），非空
     * @throws BizException OP-1001/OP-1011/OP-1013（语义见接口 javadoc）
     */
    @Override
    @Transactional
    public VisitVO admit(String visitId) {
        // 数据库读操作：按就诊号定位 visit（接诊主体）
        Visit visit = visitMapper.selectOne(Wrappers.<Visit>lambdaQuery().eq(Visit::getVisitId, visitId));
        if (visit == null) {
            throw new BizException(
                    OutpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "就诊记录不存在：visitId=" + visitId);
        }
        // 状态机单点校验（红线 5）：迁入 IN_CONSULT 唯一合法来源=WAITING（未报到/终态/声明态均拒）
        require(visit.getStatus().getCode(), VisitStatus.IN_CONSULT.getCode());
        // 分诊域联动：本就诊 CALLED 票 CAS→SERVING+serve_time（无已叫票 OP-1013，叫号≠接诊守卫）
        triageService.markServing(visitId);
        // 数据库写操作：visit CAS WAITING→IN_CONSULT+admitted_at 回填单条 UPDATE（库端 now()，与
        // 票面 serve_time 同源，禁应用服务器时钟防多实例漂移）；0 行=并发已迁移，判 OP-1011
        if (visitMapper.casAdmit(
                        visit.getId(),
                        VisitStatus.WAITING.getCode(),
                        VisitStatus.IN_CONSULT.getCode(),
                        OperatorContextHolder.get())
                == 0) {
            log.warn(
                    "接诊 CAS 落败（并发已迁移）：visitId={}，读得状态={}",
                    visitId,
                    visit.getStatus().getCode());
            throw new BizException(
                    OutpatientErrorCode.VISIT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "就诊状态不允许接诊（并发状态迁移）：visitId=" + visitId);
        }
        // 出参语义回填（列值已由库端 CAS 写入，实体不回写库）
        visit.setStatus(VisitStatus.IN_CONSULT);
        insertVisitStatusLog(visitId, VisitStatus.WAITING, VisitStatus.IN_CONSULT, "医生站接诊");
        log.info("接诊完成：visitId={}，doctorId={}（admitted_at 由库端 now() 回填）", visitId, OperatorContextHolder.get());
        return toVO(visit);
    }

    /**
     * 诊毕（接口 javadoc 契约）：词表校验→在途单据校验（显式确认豁免）→状态机校验迁移 FINISHED→
     * 回填留痕→visit.finished 发布（事务内 publishEvent，AFTER_COMMIT 出 MQ）。
     *
     * @param visitId 就诊号，非空
     * @param request 诊毕请求，非空
     * @return 诊毕后就诊出参（status=FINISHED），非空
     * @throws BizException OP-1001/OP-1011/OP-1016/OP-1018（语义见接口 javadoc）
     */
    @Override
    @Transactional
    public VisitVO finish(String visitId, FinishVisitRequest request) {
        // 数据库读操作：按就诊号定位 visit（诊毕主体）
        Visit visit = visitMapper.selectOne(Wrappers.<Visit>lambdaQuery().eq(Visit::getVisitId, visitId));
        if (visit == null) {
            throw new BizException(
                    OutpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "就诊记录不存在：visitId=" + visitId);
        }
        // 离院去向词表校验（V705 item_code 全集，词表外 OP-1018）
        if (!DISPOSITION_CODES.contains(request.disposition())) {
            throw new BizException(
                    OutpatientErrorCode.DISPOSITION_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "离院去向词表外（outpatient.disposition 八码集）：" + request.disposition());
        }
        // 诊毕前置校验：在途单据（CREATED/PENDING_FEE）未收敛须显式确认（M09 文书校验参数化提醒
        // 不拦截——not-in-scope 注记，随 M09 病案域接入）
        boolean explicitConfirm = Boolean.TRUE.equals(request.explicitConfirm());
        if (!explicitConfirm) {
            Long pending = clinicOrderMapper.selectCount(Wrappers.<ClinicOrder>lambdaQuery()
                    .eq(ClinicOrder::getVisitId, visitId)
                    .in(ClinicOrder::getStatus, OrderStatus.CREATED, OrderStatus.PENDING_FEE));
            if (pending != null && pending > 0) {
                log.warn("诊毕拒绝：在途单据未收敛且未显式确认：visitId={}，pendingOrders={}", visitId, pending);
                throw new BizException(
                        OutpatientErrorCode.FINISH_CHECK_FAILED,
                        HttpStatus.CONFLICT,
                        "诊毕校验未过：存在在途申请单（CREATED/PENDING_FEE 共 " + pending + " 张），须终态收敛或显式确认：visitId=" + visitId);
            }
        }
        // 状态机单点校验（红线 5）：IN_CONSULT→FINISHED 合法，PENDING_FEE→FINISHED 显式确认路径合法
        VisitStatus from = visit.getStatus();
        require(from.getCode(), VisitStatus.FINISHED.getCode());
        // 数据库写操作：visit CAS→FINISHED+finished_at/disposition/finish_operator 回填单条 UPDATE
        // （时间取库端 now()，禁应用服务器时钟）；0 行=并发已迁移，判 OP-1011
        if (visitMapper.casFinish(
                        visit.getId(),
                        from.getCode(),
                        VisitStatus.FINISHED.getCode(),
                        OperatorContextHolder.get(),
                        request.disposition())
                == 0) {
            log.warn("诊毕 CAS 落败（并发已迁移）：visitId={}，读得状态={}", visitId, from.getCode());
            throw new BizException(
                    OutpatientErrorCode.VISIT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "就诊状态不允许诊毕（并发状态迁移）：visitId=" + visitId);
        }
        // 出参语义回填（列值已由库端 CAS 写入，实体不回写库）
        visit.setStatus(VisitStatus.FINISHED);
        insertVisitStatusLog(visitId, from, VisitStatus.FINISHED, "医生站诊毕，离院去向=" + request.disposition());
        // 事务内发布 visit.finished（id 33，AFTER_COMMIT 出 MQ）：M09 信息页/病案与 M19 统计取数依据
        events.publishEvent(new OutpatientDomainEvent(
                OutpatientMessagingConstants.EVENT_VISIT_FINISHED,
                new VisitFinishedPayload(
                        visitId, visit.getPatientId(), request.disposition(), OperatorContextHolder.get())));
        log.info(
                "诊毕完成：visitId={}，disposition={}，finishOperator={}，explicitConfirm={}",
                visitId,
                request.disposition(),
                OperatorContextHolder.get(),
                explicitConfirm);
        return toVO(visit);
    }

    /**
     * 医生站候诊列表（接口 javadoc 契约）：本队列 WAITING/CALLED 票（未指派或指派本医生），优先级
     * 分降序+queue_time 升序；患者姓名经 patient 侧掩码收口（两跳批查，禁 N+1）。
     *
     * @param deptCode 队列标识，非空
     * @param doctorId 医生 id，非空
     * @return 候诊列表行（优先级降序）；空队列返回空列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<DoctorQueueItemVO> patientQueue(String deptCode, String doctorId) {
        // 数据库读操作：本队列候诊面票据（未指派票全员可见，指派票仅归属医生可见；同分库端权威序）
        List<QueueTicket> tickets = queueTicketMapper.selectList(Wrappers.<QueueTicket>lambdaQuery()
                .eq(QueueTicket::getQueueId, deptCode)
                .in(QueueTicket::getStatus, QUEUE_VIEW_STATUSES)
                .and(wrapper -> wrapper.isNull(QueueTicket::getDoctorId).or().eq(QueueTicket::getDoctorId, doctorId))
                .orderByDesc(QueueTicket::getPriorityScore)
                .orderByAsc(QueueTicket::getQueueTime));
        // 批量解析脱敏展示名（visitId→patientId→掩码名两跳批查，禁循环单查）
        Set<String> visitIds = tickets.stream().map(QueueTicket::getVisitId).collect(Collectors.toSet());
        Map<String, Long> visitPatients = visitIds.isEmpty()
                ? Map.of()
                : visitMapper.selectList(Wrappers.<Visit>lambdaQuery().in(Visit::getVisitId, visitIds)).stream()
                        .collect(Collectors.toMap(Visit::getVisitId, Visit::getPatientId));
        Map<Long, String> displayNames = visitPatients.isEmpty()
                ? Map.of()
                : patientNameQuery
                        .displayNamesOf(
                                visitPatients.values().stream().distinct().toList())
                        .stream()
                        .collect(Collectors.toMap(PatientDisplayName::patientId, PatientDisplayName::displayName));
        return tickets.stream()
                .map(ticket -> new DoctorQueueItemVO(
                        ticket.getId(),
                        ticket.getVisitId(),
                        ticket.getTicketNo(),
                        displayNames.get(visitPatients.get(ticket.getVisitId())),
                        ticket.getTicketType(),
                        ticket.getPriorityScore(),
                        ticket.getStatus(),
                        ALLERGY_FLAG_P1,
                        ticket.getQueueTime(),
                        ticket.getCalledCount()))
                .toList();
    }

    // ---------------------------------------------------------------- 私有辅助

    /**
     * visit 迁移留痕（红线 5 每迁必记：接诊/诊毕迁移专用，操作者取运行态上下文）。
     *
     * @param visitId 就诊号，非空
     * @param from    迁出态，非空
     * @param to      迁入态，非空
     * @param reason  迁移原因，非空
     */
    private void insertVisitStatusLog(String visitId, VisitStatus from, VisitStatus to, String reason) {
        VisitStatusLog statusLog = new VisitStatusLog();
        statusLog.setVisitId(visitId);
        statusLog.setFromStatus(from);
        statusLog.setToStatus(to);
        statusLog.setReason(reason);
        statusLog.setOperator(OperatorContextHolder.get());
        // 数据库写操作：迁移日志每迁必记（红线 5）
        visitStatusLogMapper.insert(statusLog);
    }

    /**
     * 实体 → 就诊出参投影（patientId 等长整型经全局 Long→String 定制出网——A.3-8）。
     *
     * @param visit 就诊实体（状态/时间字段已按动作回填），非空
     * @return 就诊出参，非空
     */
    private static VisitVO toVO(Visit visit) {
        return new VisitVO(
                visit.getId(),
                visit.getVisitId(),
                visit.getPatientId(),
                visit.getApptId(),
                visit.getDeptCode(),
                visit.getDoctorId(),
                visit.getVisitType(),
                visit.getIsRevisit(),
                visit.getTriageLevel(),
                visit.getStatus(),
                visit.getRegisteredAt());
    }
}
