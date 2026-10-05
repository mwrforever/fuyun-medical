package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.nursing.api.AdverseEventReportedPayload;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.cache.NursingRateGuard;
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.dto.AdverseEventCloseRequest;
import com.fuyun.nursing.dto.AdverseEventHandleRequest;
import com.fuyun.nursing.dto.AdverseEventReportRequest;
import com.fuyun.nursing.dto.AdverseEventReturnRequest;
import com.fuyun.nursing.entity.AdverseEvent;
import com.fuyun.nursing.enums.AdverseEventCategory;
import com.fuyun.nursing.enums.AdverseEventStatus;
import com.fuyun.nursing.enums.SeverityClass;
import com.fuyun.nursing.enums.SeverityGrade;
import com.fuyun.nursing.internal.NurseBoardPushEvent;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.AdverseEventMapper;
import com.fuyun.nursing.service.IAdverseEventService;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.AdverseEventStatsVO;
import com.fuyun.nursing.vo.AdverseEventVO;
import com.fuyun.nursing.vo.NurseBoardPushFrame;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 不良事件域服务实现（V1107 adverse_event，Task 10 / FU-M05-09）：上报-处置-关闭全周期。
 * 时区红线：业务时点一律北京钟面 now(HEALTHCARE_TZ)（occurredAt 由请求体传入）；
 * 状态机三支 CAS 经 AdverseEventMapper 注解 SQL（GC26：@Update + 影响行数判定 + 显式
 * deleted=0）。
 *
 * <p>非惩罚文化红线：匿名通道（显式 isAnonymous=true → reporter_id 落 NULL，不取令牌——
 * 通道不强制身份；非匿名一律令牌实名，W-72）；查询/统计出参零惩罚字段（AdverseEventVO
 * 无 reporterId 映射，统计纯计数聚合）；超时只留痕不拒绝（deadline_met=false 承载，
 * 无任何阻断分支）。
 *
 * <p>id 83 事件：落库后事务内发布（NursingEventPublisher AFTER_COMMIT 出 MQ，GC8 红线）
 * ——载荷五字段（eventNo/category/severityClass/wardId/occurredAt）契约冻结，匿名行
 * 天然合规（载荷无 reporter 字段）。
 *
 * <p>tick 超时提醒扫描段（scanAndRemindOverdue）：只读不改状态；WS 提醒推送（Task 11 已
 * 接线）——超时行按病区聚合发布 {@link NurseBoardPushEvent}（type=ADVERSE_EVENT_REMIND，
 * 样例事件号有界 5 条防刷屏），NurseBoardPushListener 推送 /topic/nursing/board/{wardId}
 * （本扫描段无事务上下文，经 fallbackExecution 立即出站）；warn 级日志保留为伴随日志。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class AdverseEventServiceImpl extends ServiceImpl<AdverseEventMapper, AdverseEvent>
        implements IAdverseEventService {

    /** 系统链路等无登录上下文场景的操作者回退值（与 V1107 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** I/II 级强制上报时限：24 小时（05 Spec FU-M05-09 冻结口径，report_deadline 计算基准） */
    private static final Duration REPORT_DEADLINE = Duration.ofHours(24);

    /** occurredAt 未来时刻容差（5 分钟——吸收前端/服务端钟差，超容差未来时点拒绝倒灌） */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    /** 单轮超时提醒扫描行数上界（有界扫描纪律，TaskOverdueServiceImpl 同族；越界行后续 tick 轮转） */
    private static final int SCAN_LIMIT = 500;

    /** 超时提醒帧样例事件号上界（防大结果集刷屏——与伴随日志同源口径） */
    private static final int REMIND_SAMPLE_LIMIT = 5;

    /** 白班窗口起点（班次映射与执行工作台同源：08:00–16:00 DAY / 16:00–24:00 EVENING / 00:00–08:00 NIGHT） */
    private static final LocalTime SHIFT_DAY_START = LocalTime.of(8, 0);

    /** 小夜班窗口起点 */
    private static final LocalTime SHIFT_EVENING_START = LocalTime.of(16, 0);

    /** 班次词表（统计零填充键序） */
    private static final String SHIFT_DAY = "DAY";

    /** 小夜班词表 */
    private static final String SHIFT_EVENING = "EVENING";

    /** 大夜班词表 */
    private static final String SHIFT_NIGHT = "NIGHT";

    /** 处置情况缺省空串（V1107 handling_note NOT NULL——上报未携处置记录时空串承载） */
    private static final String EMPTY_NOTE = "";

    private final NursingSeqGate seqGate;

    private final ApplicationEventPublisher events;

    private final IWardMetaService wardMetaService;

    private final NursingRateGuard rateGuard;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param adverseEventMapper 不良事件 mapper，非空；ServiceImpl 基座 mapper（三支 CAS 面）
     * @param seqGate            护理业务单号发号器，非空；AE 号段（fy:nursing:seq:AE:{yyyyMMdd}）
     * @param events             进程内事件发布器，非空；id 83 事件事务内发布（AFTER_COMMIT 出 MQ）
     * @param wardMetaService    病区元信息服务，非空；A-6 上报 wardId 词表守卫（wardConfig→
     *                           requireConfig 缺行 NS-1016，语义复用零重复判定）
     * @param rateGuard          护理频控守卫，非空；A-6 上报限频（每操作者窗口计数，超阈
     *                           NS-1029 429）
     */
    public AdverseEventServiceImpl(
            AdverseEventMapper adverseEventMapper,
            NursingSeqGate seqGate,
            ApplicationEventPublisher events,
            IWardMetaService wardMetaService,
            NursingRateGuard rateGuard) {
        this.seqGate = seqGate;
        this.events = events;
        this.wardMetaService = wardMetaService;
        this.rateGuard = rateGuard;
    }

    /**
     * 上报：守卫链见接口注；I/II 级 deadline 落库+上报即判定，落库后事务内发布 id 83 事件。
     * 归属口径（W-72）：默认实名（reporter_id=登录令牌身份，与前端默认 isAnonymous=false
     * 一致）、显式匿名（isAnonymous=true → reporter_id 落 NULL 不取令牌）——「reporterId==null
     * 即匿名」归一随身份令牌化作废（身份已转令牌承载，未声明匿名不再隐式归一）。
     */
    @Override
    @Transactional
    public AdverseEventVO report(AdverseEventReportRequest req) {
        // A-6 上报频控：每操作者窗口限频（匿名上报也已登录态——PR-4C 哨兵限行拒匿名于写面；
        // operator() 与审计列同源含 system 回退桶）
        if (!rateGuard.checkWithinWindow(
                "report-freq", operator(), NursingRateGuard.REPORT_LIMIT, NursingRateGuard.REPORT_WINDOW_MS)) {
            throw new BizException(
                    NursingErrorCode.REPORT_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "不良事件上报过于频繁，请稍后重试");
        }
        // 词表收口（NS-1019——禁词表外值入库；三级词表同守卫）
        AdverseEventCategory category = AdverseEventCategory.fromCode(req.category());
        if (category == null) {
            throw paramInvalid("事件类别 code 非法（八类词表外）：" + req.category());
        }
        SeverityClass severityClass = SeverityClass.fromCode(req.severityClass());
        if (severityClass == null) {
            throw paramInvalid("严重度分级 code 非法（I/II/III/IV）：" + req.severityClass());
        }
        SeverityGrade severityGrade = SeverityGrade.fromCode(req.severityGrade());
        if (severityGrade == null) {
            throw paramInvalid("严重度等级 code 非法（A~E）：" + req.severityGrade());
        }
        // A-6 wardId 词表校验：上报病区必须是护理配置词表内病区（nursing_ward_config 行存在性，
        // IWardMetaService.wardConfig→requireConfig 语义复用，缺行 NS-1016 409）——防伪造 wardId
        // 污染统计并定向驱动他病区大屏 ADVERSE_EVENT_REMIND（纯守卫：返回值不消费）
        wardMetaService.wardConfig(req.wardId());
        OffsetDateTime now = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ);
        // 未来时刻倒灌守卫（NursingAssessmentServiceImpl 同款先例+容差放宽：超容差 NS-1016 拒绝）
        if (req.occurredAt().isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new BizException(
                    NursingErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "事件发生时点不得晚于当前时间（含 5 分钟容差）：occurredAt=" + req.occurredAt());
        }
        // 归属口径（W-72）：仅显式 isAnonymous=true 走匿名通道（reporter NULL、不取令牌——通道不强制身份）；
        // 「reporterId==null 即匿名」归一随身份令牌化作废——默认实名（与前端默认 isAnonymous=false 一致）
        boolean anonymous = Boolean.TRUE.equals(req.isAnonymous());
        String operator = operator();
        // I/II 级强制上报时限落库：report_deadline=occurredAt+24h；III/IV 级不预置（NULL）
        OffsetDateTime deadline =
                severityClass.requiresDeadline() ? req.occurredAt().plus(REPORT_DEADLINE) : null;
        // 上报即判定 deadline_met=（now≤deadline）——超时补报 false 留痕（非惩罚：只留痕不拒绝）
        Boolean deadlineMet = deadline == null ? null : !now.isAfter(deadline);
        AdverseEvent row = new AdverseEvent();
        row.setEventNo(seqGate.nextNo("AE"));
        row.setCategory(category.getCode());
        row.setSeverityClass(severityClass.getCode());
        row.setSeverityGrade(severityGrade.getCode());
        row.setWardId(req.wardId());
        row.setVisitId(req.visitId());
        row.setPatientId(req.patientId());
        row.setOccurredAt(req.occurredAt());
        row.setEventSummary(req.eventSummary());
        // handling_note NOT NULL（V1107 列形态）——未携处置记录空串承载
        row.setHandlingNote(req.handlingNote() == null ? EMPTY_NOTE : req.handlingNote());
        // 非匿名一律令牌实名（W-72——请求体 reporterId 兼容保留忽略）；匿名分支不取令牌（通道不强制身份）
        row.setReporterId(anonymous ? null : contextOperatorId());
        row.setIsAnonymous(anonymous);
        row.setReportDeadline(deadline);
        row.setDeadlineMet(deadlineMet);
        row.setStatus(AdverseEventStatus.REPORTED.getCode());
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        // 数据库写操作：不良事件落库（雪花主键 ASSIGN_ID + uk_adverse_event_no 唯一）
        baseMapper.insert(row);
        // 消息发送：事务内发布上报事件（id 83 五字段冻结契约——匿名行天然合规：载荷无 reporter 字段）
        events.publishEvent(new NursingDomainEvent(
                NursingMessagingConstants.EVENT_ADVERSE_EVENT_REPORTED,
                new AdverseEventReportedPayload(
                        row.getEventNo(),
                        category.getCode(),
                        severityClass.getCode(),
                        req.wardId(),
                        req.occurredAt().toInstant())));
        log.info(
                "不良事件上报：eventNo={}，category={}，severityClass={}，wardId={}，匿名={}，deadline={}，deadlineMet={}",
                row.getEventNo(),
                category.getCode(),
                severityClass.getCode(),
                req.wardId(),
                anonymous,
                deadline,
                deadlineMet);
        return AdverseEventVO.from(row);
    }

    /** 分页查询：发生时点降序；date 缺省不限时段（在途处置队列跨日存续）。W-40 病区双轨：见接口注。 */
    @Override
    @Transactional(readOnly = true)
    public PageResult<AdverseEventVO> list(
            String category, String wardId, String status, LocalDate date, int page, int size, List<String> wardScope) {
        // 词表收口（NS-1019——查询入参禁词表外值透传 SQL）
        AdverseEventCategory categoryEnum =
                category == null || category.isBlank() ? null : AdverseEventCategory.fromCode(category);
        if (category != null && !category.isBlank() && categoryEnum == null) {
            throw paramInvalid("事件类别 code 非法（八类词表外）：" + category);
        }
        AdverseEventStatus statusEnum = status == null || status.isBlank() ? null : AdverseEventStatus.fromCode(status);
        if (status != null && !status.isBlank() && statusEnum == null) {
            throw paramInvalid("处置状态 code 非法（REPORTED/HANDLING/CLOSED）：" + status);
        }
        // W-40 空绑定集防御短路：fail-closed 下 controller 不会传空清单，此处为防御纵深——
        // 无可见病区直接返回空页，禁以空集合 in 段生成非法 SQL 或放大为全量查询
        if (wardScope != null && wardScope.isEmpty()) {
            return PageResult.of(List.of(), page, size, 0L);
        }
        OffsetDateTime windowStart = null;
        OffsetDateTime windowEnd = null;
        if (date != null) {
            // 发生日窗口（北京钟面偏移承载——occurred_at 当日闭开区间）
            ZoneOffset offset = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ).getOffset();
            windowStart = date.atStartOfDay().atOffset(offset);
            windowEnd = date.plusDays(1).atStartOfDay().atOffset(offset);
        }
        // 数据库读操作：可选过滤分页查询（发生时点降序——最新事件优先）；
        // W-40 病区双轨互斥：wardId 非空时 wardScope 恒 null（controller 守卫已校验归属，单值等值），
        // wardId 空且 scope 非空时按绑定集 in 过滤（null=不过滤——内部/哨兵语义）
        Page<AdverseEvent> result = this.lambdaQuery()
                .eq(
                        categoryEnum != null,
                        AdverseEvent::getCategory,
                        categoryEnum == null ? null : categoryEnum.getCode())
                .eq(wardId != null && !wardId.isBlank(), AdverseEvent::getWardId, wardId)
                .in(wardScope != null && !wardScope.isEmpty(), AdverseEvent::getWardId, wardScope)
                .eq(statusEnum != null, AdverseEvent::getStatus, statusEnum == null ? null : statusEnum.getCode())
                .ge(windowStart != null, AdverseEvent::getOccurredAt, windowStart)
                .lt(windowEnd != null, AdverseEvent::getOccurredAt, windowEnd)
                .orderByDesc(AdverseEvent::getOccurredAt)
                .page(new Page<>(page, size));
        List<AdverseEventVO> content =
                result.getRecords().stream().map(AdverseEventVO::from).toList();
        return PageResult.of(content, page, size, result.getTotal());
    }

    /**
     * 受理处置：REPORTED→HANDLING CAS；I/II 级超时行 deadline_met=false 留痕（非惩罚不阻断）。
     * 处置责任人一律登录令牌身份（W-72——请求体 handlerId 兼容保留忽略）。
     */
    @Override
    @Transactional
    public AdverseEventVO handle(String no, AdverseEventHandleRequest req) {
        AdverseEvent row = requireByNo(no);
        OffsetDateTime now = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ);
        // 超时留痕判定：I/II 级（deadline 非空）REPORTED 态已超 24h 处置→false；未超时透传行原值
        Boolean deadlineMet = row.getReportDeadline() != null && now.isAfter(row.getReportDeadline())
                ? Boolean.FALSE
                : row.getDeadlineMet();
        // W-72：处置责任人一律令牌身份（请求体 handlerId 兼容保留忽略）
        long handlerId = contextOperatorId();
        // 数据库写操作：受理处置 CAS（REPORTED 限定；0 行=已处置/已关闭/退回中）
        if (baseMapper.casHandle(no, handlerId, req.handlingNote(), deadlineMet, operator()) == 0) {
            throw stateNotAllowed(no, row.getStatus(), "受理处置");
        }
        row.setStatus(AdverseEventStatus.HANDLING.getCode());
        row.setHandlerId(handlerId);
        if (req.handlingNote() != null) {
            row.setHandlingNote(req.handlingNote());
        }
        row.setDeadlineMet(deadlineMet);
        log.info(
                "不良事件受理处置：eventNo={}，handlerId={}（令牌身份，W-72），超时留痕={}，deadline={}",
                no,
                handlerId,
                Boolean.FALSE.equals(deadlineMet),
                row.getReportDeadline());
        return AdverseEventVO.from(row);
    }

    /**
     * 关闭：HANDLING→CLOSED CAS；RCA 与整改措施随关闭落库（可空保留原值）。关闭动作主体
     * 一律登录令牌身份（W-72——请求体 closedBy 兼容保留忽略）。
     */
    @Override
    @Transactional
    public AdverseEventVO close(String no, AdverseEventCloseRequest req) {
        AdverseEvent row = requireByNo(no);
        // closedBy 动作主体经 updated_by 审计列承载（V1107 无独立 closed_by 列）；
        // W-72：一律令牌身份十进制串（请求体 closedBy 兼容保留忽略）
        String closedBy = String.valueOf(contextOperatorId());
        // 数据库写操作：关闭 CAS（HANDLING 限定；0 行=未处置不可关闭/已关闭）
        if (baseMapper.casClose(no, req.rcaNote(), req.correctiveAction(), closedBy) == 0) {
            throw stateNotAllowed(no, row.getStatus(), "关闭");
        }
        row.setStatus(AdverseEventStatus.CLOSED.getCode());
        if (req.rcaNote() != null) {
            row.setRcaNote(req.rcaNote());
        }
        if (req.correctiveAction() != null) {
            row.setCorrectiveAction(req.correctiveAction());
        }
        log.info(
                "不良事件关闭（RCA 与整改措施随关闭落库）：eventNo={}，closedBy={}（令牌身份，W-72），rcaNote 携带={}，correctiveAction 携带={}",
                no,
                closedBy,
                req.rcaNote() != null,
                req.correctiveAction() != null);
        return AdverseEventVO.from(row);
    }

    /**
     * 处置退回：HANDLING→REPORTED 侧支 CAS；退回原因覆写处置记录（必填留痕）。退回动作
     * 主体一律登录令牌身份（W-72——请求体 returnerId 兼容保留忽略）。
     */
    @Override
    @Transactional
    public AdverseEventVO returnEvent(String no, AdverseEventReturnRequest req) {
        AdverseEvent row = requireByNo(no);
        // returnerId 动作主体经 updated_by 审计列承载（V1107 无独立退回列）；
        // W-72：一律令牌身份十进制串（请求体 returnerId 兼容保留忽略）
        String returnerId = String.valueOf(contextOperatorId());
        // 数据库写操作：处置退回 CAS（HANDLING 限定；0 行=未处置无退回面/已关闭）
        if (baseMapper.casReturn(no, req.reason(), returnerId) == 0) {
            throw stateNotAllowed(no, row.getStatus(), "处置退回");
        }
        row.setStatus(AdverseEventStatus.REPORTED.getCode());
        row.setHandlingNote(req.reason());
        log.info("不良事件处置退回（回 REPORTED 可再处置）：eventNo={}，returnerId={}（令牌身份，W-72）", no, returnerId);
        return AdverseEventVO.from(row);
    }

    /** 分类统计：统计日窗口（缺省北京当日）按类别/病区/等级双维度/班次聚合计数+时限合规面。W-40 病区双轨：见接口注。 */
    @Override
    @Transactional(readOnly = true)
    public AdverseEventStatsVO stats(String category, String wardId, LocalDate date, List<String> wardScope) {
        // 词表收口（NS-1019——前置过滤禁词表外值透传 SQL）
        AdverseEventCategory categoryEnum =
                category == null || category.isBlank() ? null : AdverseEventCategory.fromCode(category);
        if (category != null && !category.isBlank() && categoryEnum == null) {
            throw paramInvalid("事件类别 code 非法（八类词表外）：" + category);
        }
        // 统计日缺省北京钟面当日（时区红线：禁容器时区漂移业务日）
        LocalDate statDate = date == null ? LocalDate.now(TimeConstants.HEALTHCARE_TZ) : date;
        ZoneOffset offset = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ).getOffset();
        OffsetDateTime windowStart = statDate.atStartOfDay().atOffset(offset);
        OffsetDateTime windowEnd = statDate.plusDays(1).atStartOfDay().atOffset(offset);
        // 数据库读操作：统计窗口行集（类别/病区前置过滤）；
        // W-40 空绑定集防御短路（fail-closed 防御纵深）：零行聚合走原零填充逻辑，词表维度契约不破
        List<AdverseEvent> rows = wardScope != null && wardScope.isEmpty()
                ? List.of()
                : this.lambdaQuery()
                        .eq(
                                categoryEnum != null,
                                AdverseEvent::getCategory,
                                categoryEnum == null ? null : categoryEnum.getCode())
                        .eq(wardId != null && !wardId.isBlank(), AdverseEvent::getWardId, wardId)
                        // wardId 非空时 wardScope 恒 null（controller 守卫已校验归属）；scope 非空按绑定集 in
                        .in(wardScope != null && !wardScope.isEmpty(), AdverseEvent::getWardId, wardScope)
                        .ge(AdverseEvent::getOccurredAt, windowStart)
                        .lt(AdverseEvent::getOccurredAt, windowEnd)
                        .list();
        // 词表维度零填充（稳定契约形态——前端图表/M19 消费免判空；LinkedHashMap 键序稳定）
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (AdverseEventCategory value : AdverseEventCategory.values()) {
            byCategory.put(value.getCode(), 0L);
        }
        Map<String, Long> bySeverityClass = new LinkedHashMap<>();
        for (SeverityClass value : SeverityClass.values()) {
            bySeverityClass.put(value.getCode(), 0L);
        }
        Map<String, Long> bySeverityGrade = new LinkedHashMap<>();
        for (SeverityGrade value : SeverityGrade.values()) {
            bySeverityGrade.put(value.getCode(), 0L);
        }
        Map<String, Long> byWard = new LinkedHashMap<>();
        Map<String, Long> byShift = new LinkedHashMap<>();
        byShift.put(SHIFT_DAY, 0L);
        byShift.put(SHIFT_EVENING, 0L);
        byShift.put(SHIFT_NIGHT, 0L);
        long deadlineTotal = 0;
        long deadlineMetCount = 0;
        for (AdverseEvent row : rows) {
            byCategory.merge(row.getCategory(), 1L, Long::sum);
            bySeverityClass.merge(row.getSeverityClass(), 1L, Long::sum);
            bySeverityGrade.merge(row.getSeverityGrade(), 1L, Long::sum);
            byWard.merge(row.getWardId(), 1L, Long::sum);
            byShift.merge(shiftOf(row.getOccurredAt()), 1L, Long::sum);
            if (row.getReportDeadline() != null) {
                deadlineTotal++;
                if (Boolean.TRUE.equals(row.getDeadlineMet())) {
                    deadlineMetCount++;
                }
            }
        }
        log.info(
                "不良事件分类统计：statDate={}，category 过滤={}，wardId 过滤={}，窗口行数={}，I/II 级={}，时限达成={}",
                statDate,
                category,
                wardId,
                rows.size(),
                deadlineTotal,
                deadlineMetCount);
        return new AdverseEventStatsVO(
                statDate,
                rows.size(),
                byCategory,
                bySeverityClass,
                bySeverityGrade,
                byWard,
                byShift,
                deadlineTotal,
                deadlineMetCount);
    }

    /**
     * tick 超时提醒扫描段（只读不改状态——brief 冻结语义）：见接口注。非惩罚原则承载面：
     * 超时行仅提醒不惩罚——无 deadline_met 改写、无状态迁移、无任何写操作。
     */
    @Override
    public int scanAndRemindOverdue() {
        OffsetDateTime now = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ);
        // 数据库读操作：REPORTED 态 I/II 级超时行扫描（发生时点升序有界——只读面）
        List<AdverseEvent> rows = this.lambdaQuery()
                .eq(AdverseEvent::getStatus, AdverseEventStatus.REPORTED.getCode())
                .in(AdverseEvent::getSeverityClass, SeverityClass.CLASS_I.getCode(), SeverityClass.CLASS_II.getCode())
                .lt(AdverseEvent::getReportDeadline, now)
                .orderByAsc(AdverseEvent::getReportDeadline)
                .last("LIMIT " + SCAN_LIMIT)
                .list();
        if (rows.isEmpty()) {
            // 空 tick 幂等忽略：零命中零副作用（超时行处置后自然离开 REPORTED 面）
            log.debug("不良事件 I/II 级超时提醒扫描零命中（空 tick 幂等忽略）");
            return 0;
        }
        // 消息发送：大屏超时提醒帧按病区聚合发布（本扫描段只读无事务上下文，fallback 立即出站；
        // 病区缺失行归「未知病区」聚合面承载——wardId NOT NULL 列防御，理论不触达）
        Map<String, List<AdverseEvent>> byWard =
                rows.stream().collect(Collectors.groupingBy(row -> row.getWardId() == null ? "" : row.getWardId()));
        for (Map.Entry<String, List<AdverseEvent>> entry : byWard.entrySet()) {
            events.publishEvent(new NurseBoardPushEvent(
                    entry.getKey(),
                    NurseBoardPushFrame.TYPE_ADVERSE_EVENT_REMIND,
                    new NurseBoardPushFrame.AdverseEventRemindPayload(
                            entry.getKey(),
                            entry.getValue().size(),
                            entry.getValue().stream()
                                    .map(AdverseEvent::getEventNo)
                                    .limit(REMIND_SAMPLE_LIMIT)
                                    .toList()),
                    Instant.now()));
        }
        // WS 提醒伴随日志（推送为主面；样例事件号有界 5 条防大结果集刷屏）
        log.warn(
                "不良事件 I/II 级上报时限超时提醒（不改状态，非惩罚只提醒；大屏 ADVERSE_EVENT_REMIND 帧已发布）：超时行数={}，样例={}",
                rows.size(),
                rows.stream()
                        .map(AdverseEvent::getEventNo)
                        .limit(REMIND_SAMPLE_LIMIT)
                        .toList());
        return rows.size();
    }

    // ===================== 取数与守卫辅助 =====================

    /**
     * 按不良事件号定位行（uk_adverse_event_no；未命中 NS-1025 fail-closed）。
     *
     * @param no 不良事件号，非空
     * @return 不良事件行，非空
     */
    private AdverseEvent requireByNo(String no) {
        AdverseEvent row = this.lambdaQuery().eq(AdverseEvent::getEventNo, no).one();
        if (row == null) {
            throw new BizException(NursingErrorCode.ADVERSE_EVENT_NOT_FOUND, HttpStatus.NOT_FOUND, "不良事件不存在：" + no);
        }
        return row;
    }

    /**
     * 发生时点班次映射（统计时段维度；与执行工作台班次窗口同源：08–16 DAY / 16–24 EVENING /
     * 0–8 NIGHT——按北京钟面落班）。
     *
     * @param occurredAt 发生时点，非空
     * @return 班次 code（DAY/EVENING/NIGHT），非空
     */
    private static String shiftOf(OffsetDateTime occurredAt) {
        LocalTime localTime =
                occurredAt.atZoneSameInstant(TimeConstants.HEALTHCARE_TZ).toLocalTime();
        if (localTime.isBefore(SHIFT_DAY_START)) {
            return SHIFT_NIGHT;
        }
        return localTime.isBefore(SHIFT_EVENING_START) ? SHIFT_DAY : SHIFT_EVENING;
    }

    /** 操作者取值（无登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }

    /**
     * 操作者上下文解析为员工 ID（W-72 推广——留痕主体令牌解析，照 OrderExecutionOperateServiceImpl
     * 同款）：缺失/非数字 NS-1019 拒绝（REST 链路操作者=登录护士——fail-closed）。
     */
    private static long contextOperatorId() {
        String operator = OperatorContextHolder.get();
        if (operator == null || !operator.matches("\\d+")) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "操作者标识缺失或非数字（无法定位核对/签收主体）");
        }
        return Long.parseLong(operator);
    }

    /** 入参显式格式校验失败异常构造（NS-1019 400）。 */
    private static BizException paramInvalid(String message) {
        return new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, message);
    }

    /** 状态迁移违例异常构造（NS-1026 409——含当前态与动作语义）。 */
    private static BizException stateNotAllowed(String no, String status, String action) {
        return new BizException(
                NursingErrorCode.ADVERSE_EVENT_STATE_NOT_ALLOWED,
                HttpStatus.CONFLICT,
                "不良事件状态不允许该操作（" + action + "）：eventNo=" + no + "，当前状态=" + status);
    }
}
