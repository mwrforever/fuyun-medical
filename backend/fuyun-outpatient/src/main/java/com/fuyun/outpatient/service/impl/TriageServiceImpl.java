package com.fuyun.outpatient.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.cache.QueueZsetStore;
import com.fuyun.outpatient.dto.CheckInRequest;
import com.fuyun.outpatient.dto.QueueCallRequest;
import com.fuyun.outpatient.dto.TriageAdjustRequest;
import com.fuyun.outpatient.entity.QueueTicket;
import com.fuyun.outpatient.entity.Schedule;
import com.fuyun.outpatient.entity.TriageRecord;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.entity.VisitStatusLog;
import com.fuyun.outpatient.enums.TicketStatus;
import com.fuyun.outpatient.enums.TicketType;
import com.fuyun.outpatient.enums.TriageAction;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.mapper.QueueTicketMapper;
import com.fuyun.outpatient.mapper.ScheduleMapper;
import com.fuyun.outpatient.mapper.TriageRecordMapper;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.outpatient.mapper.VisitStatusLogMapper;
import com.fuyun.outpatient.service.ITriageService;
import com.fuyun.outpatient.service.OutpatientVisitStateMachine;
import com.fuyun.outpatient.vo.QueueCalledNotice;
import com.fuyun.outpatient.vo.QueueTicketVO;
import com.fuyun.patient.api.PatientDisplayName;
import com.fuyun.patient.api.PatientNameQuery;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分诊台与候诊队列服务实现（M03 FU-M03-04，Task 7 写路径唯一入口）：报到（visit REGISTERED→
 * WAITING 状态机迁移+每迁必记 visit_status_log 红线 5+建票+ZSET 入队）、二次分诊/调级/跨队列
 * 转接（triage_record 全留痕；调级重排不改号 Spec :106）、叫号（惰性重建 Spec :210+Lua 原子出队
 * +票 CAS+双 topic WS 推送 ≤2s Spec :198）、过号降级重排与重呼、队列 REST 快照（脱敏出网）。
 * 优先级分冻结公式（类别分取最高单项+老幼残跨类叠加+封顶 999，偏差⑨经 2026-09-20 用户裁决细化）；
 * 同分排序以 queue_time 库端时间戳为权威，禁应用服务器时钟（防多实例漂移）。Redis ZSET（fy:
 * outpatient:queue:{deptCode}）仅为加速视图，queue_ticket WAITING 行为权威（重启恢复面）。
 * 资金无涉红线（裁决 7）：本类零金额逻辑。线程安全：无状态单例。装配归 OutpatientWebConfig
 *
 * @Import；com.fuyun.outpatient.service.impl 包 = JaCoCo PACKAGE LINE 1.00 覆盖对象。
 */
@Slf4j
public class TriageServiceImpl implements ITriageService {

    /** 优先级分基础分（冻结公式，偏差⑨） */
    private static final int BASE_SCORE = 100;

    /** 急诊分级顶档分（Ⅰ 级=800；Ⅱ/Ⅲ/Ⅳ 级按 100 递减：700/600/500） */
    private static final int EMERGENCY_LEVEL_TOP_SCORE = 800;

    /** 急诊分级档差（相邻分级分差） */
    private static final int EMERGENCY_LEVEL_STEP = 100;

    /** 回诊/复诊类别分（ticket_type=RETURN 来源；与急诊分级取最高单项不叠加） */
    private static final int REVISIT_SCORE = 300;

    /** 老幼残叠加分（因子含 ELDERLY/CHILD/DISABLED 任一；跨类别叠加） */
    private static final int FRAILTY_SCORE = 200;

    /** 优先级分封顶值（防 ZSET score 编码位溢出，冻结公式偏差⑨明确定义） */
    private static final int SCORE_CAP = 999;

    /** 老幼残因子词表（priority_factor JSON 词表，词表外 OP-1019 拒绝——W-22⑦ 禁裸值口径） */
    private static final Set<String> FRAILTY_FACTORS = Set.of("ELDERLY", "CHILD", "DISABLED");

    /** ZSET score 编码基数（priority_score*1e8+queue_seq；封顶 999 后量级 < 2^53 安全） */
    private static final long SCORE_ENCODE_SCALE = 100_000_000L;

    /** 过号降级幅度（ZSET 降级分=优先级分-100，下限 0；优先级分列不变 Spec :106） */
    private static final int PASS_DEMOTION = 100;

    /** 队列当日序键前缀：fy:outpatient:queue-seq:{deptCode}（A.5-1 增量键；{deptCode} 兼作 hash tag） */
    private static final String QUEUE_SEQ_KEY_PREFIX = "fy:outpatient:queue-seq:{";

    /** 队列序键后缀（Redis Cluster hash tag 收口） */
    private static final String QUEUE_SEQ_KEY_SUFFIX = "}";

    /** 队列序键 TTL 锚点时刻：当日末+2h=次日 02:00（与队列 ZSET 键 TTL 同锚，A.5-1 禁无 TTL 键） */
    private static final LocalTime QUEUE_KEY_TTL_ANCHOR = LocalTime.of(2, 0);

    /** 诊区队列 topic 前缀（大屏/语音客户端订阅面，Spec :179） */
    private static final String QUEUE_TOPIC_PREFIX = "/topic/outpatient/queue/";

    /** 医生站 topic 前缀（医生站提醒订阅面，Spec :179） */
    private static final String DOCTOR_TOPIC_PREFIX = "/topic/outpatient/doctor/";

    /** 非报到动作的分诊台缺省终端标识（triage_record.station_id NOT NULL；报到携真实终端标识） */
    private static final String DEFAULT_STATION_ID = "TRIAGE_DESK";

    private final VisitMapper visitMapper;

    private final VisitStatusLogMapper visitStatusLogMapper;

    private final QueueTicketMapper queueTicketMapper;

    private final TriageRecordMapper triageRecordMapper;

    private final ScheduleMapper scheduleMapper;

    private final QueueZsetStore queueZsetStore;

    private final StringRedisTemplate redisTemplate;

    private final SimpMessagingTemplate messagingTemplate;

    private final PatientNameQuery patientNameQuery;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import，backend 宪法 B.1）。
     *
     * @param visitMapper            就诊记录 mapper，非空；报到定位/状态 CAS/checked_in_at 回填
     * @param visitStatusLogMapper   迁移日志 mapper，非空；红线 5 每迁必记（REGISTERED→WAITING）
     * @param queueTicketMapper      票据 mapper，非空；建票/CAS/当日 WAITING 权威行读取
     * @param triageRecordMapper     分诊留痕 mapper，非空；四类动作只增留痕
     * @param scheduleMapper         排班日历 mapper，非空；叫号推送 room 展示字段关联读
     * @param queueZsetStore         队列 ZSET 存储，非空；入队/原子出队/惰性重建
     * @param redisTemplate          Redis 字符串模板，非空；队列当日序 INCR 键
     * @param messagingTemplate      STOMP 消息模板，非空；@EnableWebSocketMessageBroker 派生 Bean，
     *                               双 topic 叫号推送
     * @param patientNameQuery       患者脱敏展示名查询（patient api 契约），非空；快照/推送姓名掩码
     *                               收口（patient 侧掩码，原文不出模块）
     */
    public TriageServiceImpl(
            VisitMapper visitMapper,
            VisitStatusLogMapper visitStatusLogMapper,
            QueueTicketMapper queueTicketMapper,
            TriageRecordMapper triageRecordMapper,
            ScheduleMapper scheduleMapper,
            QueueZsetStore queueZsetStore,
            StringRedisTemplate redisTemplate,
            SimpMessagingTemplate messagingTemplate,
            PatientNameQuery patientNameQuery) {
        this.visitMapper = visitMapper;
        this.visitStatusLogMapper = visitStatusLogMapper;
        this.queueTicketMapper = queueTicketMapper;
        this.triageRecordMapper = triageRecordMapper;
        this.scheduleMapper = scheduleMapper;
        this.queueZsetStore = queueZsetStore;
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
        this.patientNameQuery = patientNameQuery;
    }

    /**
     * 分诊报到（主流程六步）：visit 定位→因子词表校验→visit CAS REGISTERED→WAITING（红线 5 合法
     * 迁移对，命中后每迁必记）→checked_in_at 回填→建票+triage_record 留痕+ZSET 入队。预约已 TAKEN
     * 直接可报到（TAKEN 即 visit 已签发 REGISTERED，前置态天然满足）。
     *
     * @param request 报到请求，非空
     * @return 候诊票据出参，非空
     * @throws BizException OP-1001/OP-1011/OP-1019（语义见接口 javadoc）
     */
    @Override
    @Transactional
    public QueueTicketVO checkIn(CheckInRequest request) {
        // 数据库读操作：按就诊号定位 visit（报到主体）
        Visit visit = visitMapper.selectOne(Wrappers.<Visit>lambdaQuery().eq(Visit::getVisitId, request.visitId()));
        if (visit == null) {
            throw new BizException(
                    OutpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "就诊记录不存在：visitId=" + request.visitId());
        }
        // 老幼残因子词表校验（词表外 OP-1019）
        List<String> factors = validateFactors(request.priorityFactors());
        // 状态机单点校验（红线 5）：迁入 WAITING 唯一合法来源=REGISTERED（终态/已报到均拒，CAS 前置）
        OutpatientVisitStateMachine.require(visit.getStatus().getCode(), VisitStatus.WAITING.getCode());
        // visit 状态 CAS（REGISTERED→WAITING 合法迁移对，红线 5）：0 行=并发迁移，判 OP-1011
        if (visitMapper.casStatus(visit.getId(), VisitStatus.REGISTERED.getCode(), VisitStatus.WAITING.getCode())
                == 0) {
            log.warn(
                    "报到拒绝：visit 状态非 REGISTERED（已报到/终态/并发迁移）：visitId={}，读得状态={}",
                    request.visitId(),
                    visit.getStatus().getCode());
            throw new BizException(
                    OutpatientErrorCode.VISIT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "就诊状态不允许报到（当前态 " + visit.getStatus().getCode() + "）：visitId=" + request.visitId());
        }
        // 数据库写操作：报到时间回填（国标采集，分诊台/自助签到时刻）。实体补写同值后再回写：
        // 上方 CAS 已置 WAITING，visit 内存态仍携 CAS 前旧值 REGISTERED——禁经 updateById 全字段
        // 回写把状态机覆写回（真栈 IT 实证：覆写后 admit 恒 OP-1011；DispenseServiceImpl.verify
        // 同款既修约定，Task 12 OutpatientFullFlowIT 首跑暴露）
        visit.setStatus(VisitStatus.WAITING);
        visit.setCheckedInAt(OffsetDateTime.now());
        visit.setUpdatedBy(OperatorContextHolder.get());
        visitMapper.updateById(visit);
        // 建票：当日序签发→票别派生（复诊→RETURN 携类别分 300）→冻结公式算分→落库
        int queueSeq = issueQueueSeq(visit.getDeptCode());
        TicketType ticketType =
                visit.getIsRevisit() != null && visit.getIsRevisit() == 1 ? TicketType.RETURN : TicketType.FIRST;
        int score = priorityScore(visit.getTriageLevel(), ticketType, factors);
        QueueTicket ticket = new QueueTicket();
        ticket.setVisitId(visit.getVisitId());
        ticket.setQueueId(visit.getDeptCode());
        ticket.setTicketNo("A" + String.format("%03d", queueSeq));
        ticket.setTicketType(ticketType);
        ticket.setPriorityScore(score);
        ticket.setQueueSeq(queueSeq);
        ticket.setStatus(TicketStatus.WAITING);
        ticket.setCreatedBy(OperatorContextHolder.get());
        ticket.setUpdatedBy(OperatorContextHolder.get());
        queueTicketMapper.insert(ticket);
        insertTriageRecord(
                visit.getVisitId(),
                visit.getDeptCode(),
                TriageAction.CHECK_IN,
                visit.getTriageLevel(),
                null,
                request.stationId(),
                factors,
                null);
        // 缓存写操作：ZSET 入队（score 编码=优先级分*1e8+当日序，同分按建行序自然就位）
        queueZsetStore.enqueue(visit.getDeptCode(), ticket.getId(), encodedScore(score, queueSeq));
        // visit 迁移留痕（红线 5 每迁必记：REGISTERED→WAITING）
        insertVisitStatusLog(visit.getVisitId());
        log.info(
                "分诊报到完成：visitId={}，ticketNo={}，deptCode={}，priorityScore={}，stationId={}",
                visit.getVisitId(),
                ticket.getTicketNo(),
                visit.getDeptCode(),
                score,
                request.stationId());
        return toVO(ticket, visit.getTriageLevel(), displayNameOf(visit.getPatientId()));
    }

    /**
     * 二次分诊/调级/跨队列转接（动作分流，全量留痕）：输入词表校验→visit/在队票定位→按动作分流
     * （RE_TRIAGE 定医生不动分；LEVEL_ADJUST 重算分重排不改号；QUEUE_TRANSFER 旧队放票新队建票）。
     *
     * @param request 分诊调整请求，非空
     * @return 调整后票据出参（转队列为新票），非空
     * @throws BizException OP-1001/OP-1012/OP-1019（语义见接口 javadoc）
     */
    @Override
    @Transactional
    public QueueTicketVO adjust(TriageAdjustRequest request) {
        // 动作词表校验（词表外/报到动作误入本端点均 OP-1019——报到走 /triage/check-in 专用端点）
        TriageAction action;
        try {
            action = TriageAction.fromCode(request.action());
        } catch (IllegalArgumentException e) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "分诊动作词表外：" + request.action());
        }
        if (action == TriageAction.CHECK_IN) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "报到动作走 /triage/check-in 专用端点，本端点不接受 CHECK_IN");
        }
        // 数据库读操作：visit 与在队票定位（在队=WAITING/PASSED——过号降级重排不改号的调级对象）
        Visit visit = visitMapper.selectOne(Wrappers.<Visit>lambdaQuery().eq(Visit::getVisitId, request.visitId()));
        if (visit == null) {
            throw new BizException(
                    OutpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "就诊记录不存在：visitId=" + request.visitId());
        }
        // 因子/分级词表校验（重算为全量口径：未携带因子按无老幼残因子重算，分级越界 OP-1019）
        List<String> factors = validateFactors(request.priorityFactors());
        if (request.triageLevel() != null && (request.triageLevel() < 1 || request.triageLevel() > 4)) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "急诊分级词表外（Ⅰ~Ⅳ=1~4）：" + request.triageLevel());
        }
        // LEVEL_ADJUST 理由必携校验（与词表/越界校验同层前置，零副作用拒绝；留痕落
        // triage_record.reason 供质控回溯——Spec D-9「调级理由必填」限定调级动作，
        // RE_TRIAGE/QUEUE_TRANSFER 无理由语义不强制，避免误伤）
        if (action == TriageAction.LEVEL_ADJUST
                && (request.reason() == null || request.reason().isBlank())) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "调级动作必须携带理由 reason（triage_record 留痕追溯）：visitId=" + request.visitId());
        }
        QueueTicket ticket = queueTicketMapper.selectOne(Wrappers.<QueueTicket>lambdaQuery()
                .eq(QueueTicket::getVisitId, request.visitId())
                .in(QueueTicket::getStatus, TicketStatus.WAITING, TicketStatus.PASSED));
        if (ticket == null) {
            throw new BizException(
                    OutpatientErrorCode.TICKET_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "在队候诊票不存在（未报到或已离队）：visitId=" + request.visitId());
        }
        // 分级快照回写 visit（国标字段分诊台写入；调级重算以 visit 当前分级为口径）
        if (request.triageLevel() != null) {
            visit.setTriageLevel(request.triageLevel());
            visit.setUpdatedBy(OperatorContextHolder.get());
            visitMapper.updateById(visit);
        }
        // 动作分流（CHECK_IN 已前置守卫拒绝，枚举四值穷举其余三值——switch 语句无需 default 死分支）
        switch (action) {
            case RE_TRIAGE -> {
                ticket.setDoctorId(request.doctorId());
                ticket.setUpdatedBy(OperatorContextHolder.get());
                queueTicketMapper.updateById(ticket);
            }
            case LEVEL_ADJUST -> adjustLevel(ticket, visit, factors);
            case QUEUE_TRANSFER -> ticket = transferQueue(ticket, visit, request, factors);
        }
        insertTriageRecord(
                visit.getVisitId(),
                ticket.getQueueId(),
                action,
                visit.getTriageLevel(),
                ticket.getDoctorId(),
                DEFAULT_STATION_ID,
                factors,
                request.reason());
        log.info(
                "分诊调整完成：action={}，visitId={}，ticketNo={}，queueId={}，priorityScore={}，doctorId={}",
                action.getCode(),
                visit.getVisitId(),
                ticket.getTicketNo(),
                ticket.getQueueId(),
                ticket.getPriorityScore(),
                ticket.getDoctorId());
        return toVO(ticket, visit.getTriageLevel(), displayNameOf(visit.getPatientId()));
    }

    /**
     * 叫号（惰性重建→原子出队→CAS→推送）：前置按当日待重叫权威行（WAITING 候诊+PASSED 过号
     * 再入——fix round 1 Important-2 裁决①，PASSED 票重启后不静默跌出队列）惰性重建 ZSET（键在位
     * 零写，幂等禁回灌非在队票）；出队首个「未指派或指派一致」票后按票行当前态 CAS→CALLED
     * （WAITING→CALLED 首叫与 PASSED→CALLED 队内重叫共用，called_count+1+call_time）并双 topic
     * 推送。叫号≠接诊。
     *
     * @param request 叫号请求，非空
     * @return 叫中票据出参；队列空返回 null（200 空语义）
     * @throws BizException OP-1012/OP-1013（语义见接口 javadoc）
     */
    @Override
    @Transactional
    public QueueTicketVO call(QueueCallRequest request) {
        // 惰性重建前置（Spec :210 重启恢复）：当日待重叫权威行（WAITING+PASSED）→pk→编码分映射
        // →rebuildIfMissing
        List<QueueTicket> waiting = queueTicketMapper.selectWaiting(request.deptCode());
        Map<Long, Long> ticketScores = waiting.stream()
                .collect(Collectors.toMap(
                        QueueTicket::getId, ticket -> encodedScore(ticket.getPriorityScore(), ticket.getQueueSeq())));
        int rebuilt = queueZsetStore.rebuildIfMissing(request.deptCode(), ticketScores);
        log.info(
                "叫号前置惰性重建完成（-1=键在位零写）：deptCode={}，rebuildResult={}，在队票数={}",
                request.deptCode(),
                rebuilt,
                waiting.size());
        // 缓存读操作：ZSET 原子出队（首个可叫态未指派/指派一致票；无匹配=null）
        Long polled = queueZsetStore.pollTop(request.deptCode(), request.doctorId());
        if (polled == null) {
            log.info("叫号空队返回（200 空语义）：deptCode={}，doctorId={}", request.deptCode(), request.doctorId());
            return null;
        }
        // 数据库读操作：出队票行定位（pollTop 保证成员在位，行缺失属数据异常防御性拒绝）
        QueueTicket ticket = queueTicketMapper.selectById(polled);
        if (ticket == null) {
            throw new BizException(
                    OutpatientErrorCode.TICKET_NOT_FOUND, HttpStatus.NOT_FOUND, "候诊票据不存在：ticketId=" + polled);
        }
        // 数据库写操作：叫号 CAS（from=票行当前态——WAITING 首叫/PASSED 过号再入重叫共用，至
        // CALLED+called_count 累加+call_time 回填）；0 行=并发已叫/已迁移
        if (queueTicketMapper.casCall(ticket.getId(), ticket.getStatus().getCode(), OperatorContextHolder.get()) == 0) {
            log.warn("叫号 CAS 落败（并发已叫/已迁移）：ticketId={}，ticketNo={}", ticket.getId(), ticket.getTicketNo());
            throw new BizException(
                    OutpatientErrorCode.TICKET_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "票据状态不允许叫号（并发已叫或已迁移）：ticketNo=" + ticket.getTicketNo());
        }
        ticket.setStatus(TicketStatus.CALLED);
        ticket.setCalledCount(ticket.getCalledCount() == null ? 1 : ticket.getCalledCount() + 1);
        pushCalled(ticket, request.deptCode(), request.doctorId());
        log.info(
                "叫号完成：ticketNo={}，deptCode={}，doctorId={}，calledCount={}",
                ticket.getTicketNo(),
                request.deptCode(),
                request.doctorId(),
                ticket.getCalledCount());
        return toVOWithVisit(ticket);
    }

    /**
     * 过号（CALLED→PASSED，降级重入）：CAS 命中后以降级分（优先级分-100，下限 0）重入 ZSET 保持
     * WAITING 语义（票号与优先级分列不变——过号降级重排不改号 Spec :106）。
     *
     * @param ticketId 票据主键，非空
     * @return 过号票据出参（PASSED），非空
     * @throws BizException OP-1012/OP-1013（语义见接口 javadoc）
     */
    @Override
    @Transactional
    public QueueTicketVO pass(long ticketId) {
        // 数据库读操作：票据定位
        QueueTicket ticket = queueTicketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BizException(
                    OutpatientErrorCode.TICKET_NOT_FOUND, HttpStatus.NOT_FOUND, "候诊票据不存在：ticketId=" + ticketId);
        }
        // 数据库写操作：过号 CAS（CALLED→PASSED）；0 行=非 CALLED 态/并发已迁移，判 OP-1013
        if (queueTicketMapper.casStatus(
                        ticket.getId(),
                        TicketStatus.CALLED.getCode(),
                        TicketStatus.PASSED.getCode(),
                        OperatorContextHolder.get())
                == 0) {
            log.warn(
                    "过号 CAS 落败（非 CALLED 态/并发迁移）：ticketId={}，ticketNo={}，当前态={}",
                    ticket.getId(),
                    ticket.getTicketNo(),
                    ticket.getStatus().getCode());
            throw new BizException(
                    OutpatientErrorCode.TICKET_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "票据状态不允许过号（当前态 " + ticket.getStatus().getCode() + "）：ticketNo=" + ticket.getTicketNo());
        }
        // 缓存写操作：降级分重入 ZSET（下限 0；编码=降级分*1e8+当日序，同分按建行序自然就位）
        int demoted = Math.max(0, ticket.getPriorityScore() - PASS_DEMOTION);
        queueZsetStore.enqueue(ticket.getQueueId(), ticket.getId(), encodedScore(demoted, ticket.getQueueSeq()));
        ticket.setStatus(TicketStatus.PASSED);
        log.info(
                "过号降级重排完成（不改号）：ticketNo={}，queueId={}，原分={}，降级分={}",
                ticket.getTicketNo(),
                ticket.getQueueId(),
                ticket.getPriorityScore(),
                demoted);
        return toVOWithVisit(ticket);
    }

    /**
     * 重呼（PASSED→CALLED 重复叫）：CAS 命中后 called_count 累加并重复双 topic 推送（大屏/医生站
     * 再次播报）；医生 topic 归属=票指派医生，未指派回落操作者面板。
     *
     * @param ticketId 票据主键，非空
     * @return 重呼票据出参（CALLED），非空
     * @throws BizException OP-1012/OP-1013（语义见接口 javadoc）
     */
    @Override
    @Transactional
    public QueueTicketVO recall(long ticketId) {
        // 数据库读操作：票据定位
        QueueTicket ticket = queueTicketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BizException(
                    OutpatientErrorCode.TICKET_NOT_FOUND, HttpStatus.NOT_FOUND, "候诊票据不存在：ticketId=" + ticketId);
        }
        // 数据库写操作：重呼 CAS（PASSED→CALLED，casCall 复用叫号计数/时间回填）；0 行判 OP-1013
        if (queueTicketMapper.casCall(ticket.getId(), TicketStatus.PASSED.getCode(), OperatorContextHolder.get())
                == 0) {
            log.warn(
                    "重呼 CAS 落败（非 PASSED 态/并发迁移）：ticketId={}，ticketNo={}，当前态={}",
                    ticket.getId(),
                    ticket.getTicketNo(),
                    ticket.getStatus().getCode());
            throw new BizException(
                    OutpatientErrorCode.TICKET_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "票据状态不允许重呼（当前态 " + ticket.getStatus().getCode() + "）：ticketNo=" + ticket.getTicketNo());
        }
        ticket.setStatus(TicketStatus.CALLED);
        ticket.setCalledCount(ticket.getCalledCount() == null ? 1 : ticket.getCalledCount() + 1);
        // 医生 topic 归属：票指派医生优先，未指派回落操作者（分诊台重呼未定医生票的提醒面板）
        String doctorId = ticket.getDoctorId() != null ? ticket.getDoctorId() : OperatorContextHolder.get();
        pushCalled(ticket, ticket.getQueueId(), doctorId);
        log.info(
                "重呼完成：ticketNo={}，queueId={}，calledCount={}",
                ticket.getTicketNo(),
                ticket.getQueueId(),
                ticket.getCalledCount());
        return toVOWithVisit(ticket);
    }

    /**
     * 队列 REST 快照：DB 权威查询（优先级分降序+queue_time 建行时间升序——同分序库端权威，禁应用
     * 时钟），visit→patientId→脱敏展示名两跳批查（禁 N+1）。
     *
     * @param queueId 队列标识，非空
     * @param status  状态过滤词表值，可空
     * @return 票据出参列表（优先级降序）；空队列返回空列表
     * @throws BizException OP-1019（状态词表外）
     */
    @Override
    @Transactional(readOnly = true)
    public List<QueueTicketVO> snapshot(String queueId, String status) {
        // 数据库读操作：队列票据查询（排序权威=优先级分降序+建行时间升序，偏差⑨；排序入参零应用时钟）
        LambdaQueryWrapper<QueueTicket> wrapper = Wrappers.<QueueTicket>lambdaQuery()
                .eq(QueueTicket::getQueueId, queueId)
                .orderByDesc(QueueTicket::getPriorityScore)
                .orderByAsc(QueueTicket::getQueueTime);
        if (status != null && !status.isBlank()) {
            TicketStatus statusEnum;
            try {
                statusEnum = TicketStatus.fromCode(status);
            } catch (IllegalArgumentException e) {
                throw new BizException(
                        OutpatientErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "票据状态词表外：" + status);
            }
            wrapper.eq(QueueTicket::getStatus, statusEnum);
        }
        List<QueueTicket> tickets = queueTicketMapper.selectList(wrapper);
        // 批量读取 visit 行（患者主索引+分诊级别快照同源单次批查——D-2 快照面零新增查询，禁循环单查）
        Set<String> visitIds = tickets.stream().map(QueueTicket::getVisitId).collect(Collectors.toSet());
        Map<String, Visit> visits = visitIds.isEmpty()
                ? Map.of()
                : visitMapper.selectList(Wrappers.<Visit>lambdaQuery().in(Visit::getVisitId, visitIds)).stream()
                        .collect(Collectors.toMap(Visit::getVisitId, Function.identity()));
        // 批量解析脱敏展示名（visit→patientId→掩码名批查，禁 N+1）
        Map<Long, String> displayNames =
                patientNameQuery
                        .displayNamesOf(visits.values().stream()
                                .map(Visit::getPatientId)
                                .distinct()
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(PatientDisplayName::patientId, PatientDisplayName::displayName));
        return tickets.stream()
                .map(ticket -> {
                    Visit visit = visits.get(ticket.getVisitId());
                    return toVO(
                            ticket,
                            visit == null ? null : visit.getTriageLevel(),
                            visit == null ? null : displayNames.get(visit.getPatientId()));
                })
                .toList();
    }

    /**
     * 接诊联动（ITriageService.markServing，Task 8 随 IVisitService 扩展交付）：定位本就诊 CALLED
     * 票（最近叫号优先；P1 口径——本就诊的 CALLED 票即本医生票，叫号医生与接诊医生同一诊室流程，
     * 票面 doctor_id 指派不作为过滤谓词）并 CAS→SERVING+serve_time 回填（国标接诊时间，库端
     * now()，禁应用服务器时钟）。无 CALLED 票（未叫号/已过号/已接诊）或并发迁移 OP-1013 拒绝。
     *
     * @param visitId 就诊号，非空
     * @return 接诊后票据出参（status=SERVING，含脱敏姓名），非空
     * @throws BizException OP-1013（无已叫号票据或票据并发迁移）时触发
     */
    @Override
    @Transactional
    public QueueTicketVO markServing(String visitId) {
        // 数据库读操作：本就诊 CALLED 票定位（最近叫号优先——重复叫号后以末次叫票为准）
        QueueTicket ticket = queueTicketMapper.selectOne(Wrappers.<QueueTicket>lambdaQuery()
                .eq(QueueTicket::getVisitId, visitId)
                .eq(QueueTicket::getStatus, TicketStatus.CALLED)
                .orderByDesc(QueueTicket::getCallTime)
                .last("LIMIT 1"));
        if (ticket == null) {
            log.warn("接诊拒绝：无已叫号票据（未叫号/已过号/已接诊）：visitId={}", visitId);
            throw new BizException(
                    OutpatientErrorCode.TICKET_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "接诊须先叫号（无 CALLED 态票据）：visitId=" + visitId);
        }
        // 数据库写操作：接诊 CAS（CALLED→SERVING+serve_time）；0 行=并发已迁移，判 OP-1013
        if (queueTicketMapper.casAdmit(ticket.getId(), OperatorContextHolder.get()) == 0) {
            log.warn("接诊 CAS 落败（并发已迁移）：ticketId={}，ticketNo={}", ticket.getId(), ticket.getTicketNo());
            throw new BizException(
                    OutpatientErrorCode.TICKET_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "票据状态不允许接诊（并发已迁移）：ticketNo=" + ticket.getTicketNo());
        }
        ticket.setStatus(TicketStatus.SERVING);
        log.info(
                "接诊联动完成（CALLED→SERVING+serve_time）：ticketNo={}，visitId={}，operator={}",
                ticket.getTicketNo(),
                visitId,
                OperatorContextHolder.get());
        return toVOWithVisit(ticket);
    }

    // ---------------------------------------------------------------- 私有辅助

    /**
     * 调级重算（票号不变——过号降级重排不改号 Spec :106）：按 visit 当前分级（已随请求回写）+票别+
     * 因子重算优先级分，ZSET 以 remove+enqueue 同 member 换分重排。
     *
     * @param ticket 在队票据，非空
     * @param visit  所属就诊（分级已随请求回写），非空
     * @param factors 校验后的老幼残因子，非空
     */
    private void adjustLevel(QueueTicket ticket, Visit visit, List<String> factors) {
        int newScore = priorityScore(visit.getTriageLevel(), ticket.getTicketType(), factors);
        ticket.setPriorityScore(newScore);
        ticket.setUpdatedBy(OperatorContextHolder.get());
        queueTicketMapper.updateById(ticket);
        // 缓存写操作：ZSET 重排（同 member 换分——票号不变，Spec :106）
        queueZsetStore.remove(ticket.getQueueId(), ticket.getId());
        queueZsetStore.enqueue(ticket.getQueueId(), ticket.getId(), encodedScore(newScore, ticket.getQueueSeq()));
    }

    /**
     * 跨队列转接（旧队放票+新队建票重算分）：旧队 ZSET remove→旧票 CAS→CANCELLED→新队当日序签发→
     * 新票落库（新 queue_id/票号/当日序，uk_ticket_visit 以 (visit_id, queue_seq) 区分新旧票）→
     * 新队入队。旧票 CAS 落败=并发迁移，fail-fast 整单回滚。
     *
     * @param ticket  旧队在队票据，非空
     * @param visit   所属就诊，非空
     * @param request 转队列请求（targetQueue 必携），非空
     * @param factors 校验后的老幼残因子，非空
     * @return 新队列新票（未含留痕），非空
     * @throws BizException OP-1019（targetQueue 缺失）时触发
     * @throws IllegalStateException 旧票 CAS 并发落败（数据异常，交事务回滚留痕）
     */
    private QueueTicket transferQueue(
            QueueTicket ticket, Visit visit, TriageAdjustRequest request, List<String> factors) {
        if (request.targetQueue() == null || request.targetQueue().isBlank()) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "跨队列转接必须携带目标队列 targetQueue：visitId=" + request.visitId());
        }
        // 数据库写操作：旧票 CAS→CANCELLED（先 CAS 后动 Redis——并发落败 fail-fast 时零 Redis 残留）
        if (queueTicketMapper.casStatus(
                        ticket.getId(),
                        ticket.getStatus().getCode(),
                        TicketStatus.CANCELLED.getCode(),
                        OperatorContextHolder.get())
                == 0) {
            throw new IllegalStateException("跨队列转接失败：旧票并发状态迁移（CAS 落败）：ticketId=" + ticket.getId());
        }
        // 缓存写操作：旧队移除（CAS 命中后放票，防旧队继续叫到已转票）
        queueZsetStore.remove(ticket.getQueueId(), ticket.getId());
        int queueSeq = issueQueueSeq(request.targetQueue());
        int newScore = priorityScore(visit.getTriageLevel(), ticket.getTicketType(), factors);
        QueueTicket fresh = new QueueTicket();
        fresh.setVisitId(visit.getVisitId());
        fresh.setQueueId(request.targetQueue());
        fresh.setTicketNo("A" + String.format("%03d", queueSeq));
        fresh.setTicketType(ticket.getTicketType());
        fresh.setPriorityScore(newScore);
        fresh.setQueueSeq(queueSeq);
        fresh.setStatus(TicketStatus.WAITING);
        fresh.setCreatedBy(OperatorContextHolder.get());
        fresh.setUpdatedBy(OperatorContextHolder.get());
        queueTicketMapper.insert(fresh);
        queueZsetStore.enqueue(request.targetQueue(), fresh.getId(), encodedScore(newScore, queueSeq));
        log.info(
                "跨队列转接完成：visitId={}，oldQueue={}，newQueue={}，newTicketNo={}，newScore={}",
                visit.getVisitId(),
                ticket.getQueueId(),
                fresh.getQueueId(),
                fresh.getTicketNo(),
                newScore);
        return fresh;
    }

    /**
     * 叫号 WS 双 topic 推送（端到端 ≤2s，Spec :198）：诊区队列 topic（大屏/语音）+医生 topic（医生站
     * 提醒）；载荷脱敏口径=ticketNo+姓名掩码，不带 visitId/patientId 原始标识。
     *
     * @param ticket   已置 CALLED 票据，非空
     * @param deptCode 队列标识，非空
     * @param doctorId 叫号医生 id，非空
     */
    private void pushCalled(QueueTicket ticket, String deptCode, String doctorId) {
        QueueCalledNotice notice = new QueueCalledNotice(
                "CALLED",
                ticket.getTicketNo(),
                maskedNameOf(ticket.getVisitId()),
                doctorId,
                roomOf(deptCode, doctorId));
        // 消息发送：双 topic 推送（队列快照 REST 之外的实时通道）
        messagingTemplate.convertAndSend(QUEUE_TOPIC_PREFIX + deptCode, notice);
        messagingTemplate.convertAndSend(DOCTOR_TOPIC_PREFIX + doctorId, notice);
        log.info(
                "叫号 WS 推送完成：queueTopic={}，doctorTopic={}，ticketNo={}",
                QUEUE_TOPIC_PREFIX + deptCode,
                DOCTOR_TOPIC_PREFIX + doctorId,
                ticket.getTicketNo());
    }

    /**
     * 叫号诊室解析（展示字段）：按队列+医生+当日定位排班取 room（同日多时段取 id 最小首班）；
     * 排班缺失/未配置返回 null（载荷字段可空）。
     *
     * @param deptCode 队列标识，非空
     * @param doctorId 医生 id，非空
     * @return 诊室名；无排班为 null
     */
    private String roomOf(String deptCode, String doctorId) {
        Schedule schedule = scheduleMapper.selectOne(Wrappers.<Schedule>lambdaQuery()
                .eq(Schedule::getDeptCode, deptCode)
                .eq(Schedule::getDoctorId, doctorId)
                .eq(Schedule::getSchedDate, LocalDate.now())
                .orderByAsc(Schedule::getId)
                .last("LIMIT 1"));
        return schedule == null ? null : schedule.getRoom();
    }

    /**
     * 优先级分冻结公式（偏差⑨经 2026-09-20 用户裁决细化）：min(999, 100 基础分 + max(急诊分级
     * 1/2/3/4→800/700/600/500（非急诊 0）, 回诊/复诊 300) + 老幼残 200)——类别分取最高单项不叠加，
     * 老幼残跨类叠加；绿通 900 为声明值（P1 恒不触发——green_channel_flag 恒 0 且无因子入口，
     * 公式不落分支，随绿通域接入再扩展）；封顶 999。
     *
     * @param triageLevel 急诊分级（1~4），可空
     * @param ticketType  票别，非空
     * @param factors     校验后的老幼残因子，非空
     * @return 优先级分（0~999）
     */
    private static int priorityScore(Integer triageLevel, TicketType ticketType, List<String> factors) {
        int category = Math.max(levelScore(triageLevel), ticketType == TicketType.RETURN ? REVISIT_SCORE : 0);
        int frailty = factors.stream().anyMatch(FRAILTY_FACTORS::contains) ? FRAILTY_SCORE : 0;
        return Math.min(SCORE_CAP, BASE_SCORE + category + frailty);
    }

    /**
     * 急诊分级分值：Ⅰ~Ⅳ=1~4 → 800/700/600/500（800 顶档按 100 递减）；未分级（null）为 0。
     * 分级词表由写入侧校验保证（adjust OP-1019）。
     *
     * @param triageLevel 急诊分级，可空
     * @return 分级类别分
     */
    private static int levelScore(Integer triageLevel) {
        return triageLevel == null ? 0 : EMERGENCY_LEVEL_TOP_SCORE - (triageLevel - 1) * EMERGENCY_LEVEL_STEP;
    }

    /**
     * 老幼残因子词表校验：null 视为无因子；词表外值 OP-1019 拒绝（W-22⑦ 禁裸值口径）。
     *
     * @param factors 因子清单，可空
     * @return 校验后因子清单（null 归一为空清单），非空
     * @throws BizException OP-1019（词表外因子）时触发
     */
    private static List<String> validateFactors(List<String> factors) {
        if (factors == null) {
            return List.of();
        }
        for (String factor : factors) {
            if (!FRAILTY_FACTORS.contains(factor)) {
                throw new BizException(
                        OutpatientErrorCode.PARAM_FORMAT_INVALID,
                        HttpStatus.BAD_REQUEST,
                        "优先级因子词表外（ELDERLY/CHILD/DISABLED）：" + factor);
            }
        }
        return factors;
    }

    /**
     * 因子清单转 JSON 文本（triage_record.priority_factor 列承载，如 ["ELDERLY","CHILD"]）；
     * 空清单返回 null（列可空）。
     *
     * @param factors 校验后因子清单，非空
     * @return JSON 文本；空清单为 null
     */
    private static String factorJson(List<String> factors) {
        return factors.isEmpty()
                ? null
                : factors.stream().map(factor -> "\"" + factor + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * ZSET score 编码：priority_score*1e8+queue_seq（queue_seq 建行事务内当日序、单调递增与
     * queue_time 建行时序一致；封顶 999 后量级 < 2^53 安全）。long 承载（int 超上限）。
     *
     * @param priorityScore 优先级分（0~999）
     * @param queueSeq      队列当日序
     * @return 编码分
     */
    private static long encodedScore(int priorityScore, int queueSeq) {
        return (long) priorityScore * SCORE_ENCODE_SCALE + queueSeq;
    }

    /**
     * 签发队列当日序（Redis INCR 键 fy:outpatient:queue-seq:{deptCode}，A.5-1 增量键先例=裁决 11
     * visit-seq 同型；首签续期 TTL=当日末+2h 与队列键同锚）。
     *
     * @param deptCode 队列标识，非空
     * @return 当日序（1 起）
     * @throws IllegalStateException Redis 流水返回空（连接异常由底层异常上抛），建票无法落号
     */
    private int issueQueueSeq(String deptCode) {
        String seqKey = QUEUE_SEQ_KEY_PREFIX + deptCode + QUEUE_SEQ_KEY_SUFFIX;
        Long seq = redisTemplate.opsForValue().increment(seqKey);
        if (seq == null) {
            throw new IllegalStateException("队列当日序签发失败：Redis 流水返回空，seqKey=" + seqKey);
        }
        if (seq == 1L) {
            redisTemplate.expire(seqKey, ttlOfTodayEndPlus2h());
        }
        return seq.intValue();
    }

    /**
     * 队列键/序键 TTL 计算：当日末+2h（次日 02:00，与池键对账锚同值；队列为当日语义，隔日自然过期）。
     *
     * @return 距锚点时刻的时长（恒为正）
     */
    private static Duration ttlOfTodayEndPlus2h() {
        return Duration.between(
                LocalDateTime.now(), LocalDateTime.now().plusDays(1).with(QUEUE_KEY_TTL_ANCHOR));
    }

    /**
     * 分诊动作留痕落库（只增语义，四类动作共用收口；非报到动作 station_id 取分诊台缺省标识）。
     *
     * @param visitId     就诊号，非空
     * @param targetQueue 动作目标队列，非空
     * @param action      分诊动作，非空
     * @param triageLevel 分级快照，可空
     * @param doctorId    指派医生，可空
     * @param stationId   终端标识，可空（空取缺省标识）
     * @param factors     校验后因子清单，非空
     * @param reason      动作理由，可空
     */
    private void insertTriageRecord(
            String visitId,
            String targetQueue,
            TriageAction action,
            Integer triageLevel,
            String doctorId,
            String stationId,
            List<String> factors,
            String reason) {
        TriageRecord record = new TriageRecord();
        record.setVisitId(visitId);
        record.setStationId(stationId == null ? DEFAULT_STATION_ID : stationId);
        record.setAction(action);
        record.setTriageLevel(triageLevel);
        record.setTargetQueue(targetQueue);
        record.setDoctorId(doctorId);
        record.setPriorityFactor(factorJson(factors));
        record.setNurseId(OperatorContextHolder.get());
        record.setReason(reason);
        record.setCreatedBy(OperatorContextHolder.get());
        record.setUpdatedBy(OperatorContextHolder.get());
        // 数据库写操作：分诊动作留痕（只增）
        triageRecordMapper.insert(record);
    }

    /**
     * visit 迁移留痕（红线 5 每迁必记：REGISTERED→WAITING，报到动作专用）。
     *
     * @param visitId 就诊号，非空
     */
    private void insertVisitStatusLog(String visitId) {
        VisitStatusLog statusLog = new VisitStatusLog();
        statusLog.setVisitId(visitId);
        statusLog.setFromStatus(VisitStatus.REGISTERED);
        statusLog.setToStatus(VisitStatus.WAITING);
        statusLog.setReason("分诊台报到入队");
        statusLog.setOperator(OperatorContextHolder.get());
        // 数据库写操作：迁移日志每迁必记（红线 5）
        visitStatusLogMapper.insert(statusLog);
    }

    /**
     * 单患者脱敏展示名解析（patient 侧掩码收口；无命中返回 null）。
     *
     * @param patientId 患者主索引
     * @return 脱敏展示名；无命中为 null
     */
    private String displayNameOf(long patientId) {
        List<PatientDisplayName> names = patientNameQuery.displayNamesOf(List.of(patientId));
        return names.isEmpty() ? null : names.get(0).displayName();
    }

    /**
     * 按就诊号解析脱敏展示名（visitId→patientId→掩码名两跳；visit 缺失/无命中返回 null）。
     *
     * @param visitId 就诊号，非空
     * @return 脱敏展示名；无命中为 null
     */
    private String maskedNameOf(String visitId) {
        Visit visit = visitMapper.selectOne(Wrappers.<Visit>lambdaQuery().eq(Visit::getVisitId, visitId));
        return visit == null ? null : displayNameOf(visit.getPatientId());
    }

    /**
     * 实体 → 票据出参投影（patientName 为脱敏展示名出网，无证件号等敏感字段——Spec §9 脱敏红线；
     * triageLevel 取 visit 权威快照，由调用方按各自路径供给——D-2）。
     *
     * @param ticket      票据实体，非空
     * @param triageLevel 分诊级别快照（visit.triage_level），可空（未分级）
     * @param patientName 脱敏展示名，可空
     * @return 票据出参，非空
     */
    private static QueueTicketVO toVO(QueueTicket ticket, Integer triageLevel, String patientName) {
        return new QueueTicketVO(
                ticket.getId(),
                ticket.getVisitId(),
                ticket.getQueueId(),
                ticket.getTicketNo(),
                ticket.getTicketType(),
                ticket.getDoctorId(),
                ticket.getPriorityScore(),
                ticket.getQueueSeq(),
                ticket.getQueueTime(),
                ticket.getCalledCount(),
                ticket.getCallTime(),
                ticket.getStatus(),
                patientName,
                triageLevel);
    }

    /**
     * 单票路径出参组装（call/pass/recall/markServing 共用）：按票据就诊号一次读取 visit 行，同时
     * 取分诊级别快照（D-2：queue_ticket 无此列，权威在 visit.triage_level）与患者脱敏展示名，
     * 禁拆两次查询；visit 缺失（数据异常防御）时两字段均 null，与既有 maskedNameOf 空语义一致。
     *
     * @param ticket 票据实体，非空
     * @return 票据出参，非空
     */
    private QueueTicketVO toVOWithVisit(QueueTicket ticket) {
        // 数据库读操作：visit 业务号定位（分级快照+患者主索引单次读取）
        Visit visit = visitMapper.selectOne(Wrappers.<Visit>lambdaQuery().eq(Visit::getVisitId, ticket.getVisitId()));
        return toVO(
                ticket,
                visit == null ? null : visit.getTriageLevel(),
                visit == null ? null : displayNameOf(visit.getPatientId()));
    }
}
