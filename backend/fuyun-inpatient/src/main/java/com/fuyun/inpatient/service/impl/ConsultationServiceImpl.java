package com.fuyun.inpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.ConsultationPayload;
import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.ConsultationCreateRequest;
import com.fuyun.inpatient.dto.ConsultationOpinionRequest;
import com.fuyun.inpatient.entity.Consultation;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.enums.ConsultationLevel;
import com.fuyun.inpatient.enums.ConsultationStatus;
import com.fuyun.inpatient.enums.ConsultationUrgency;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.ConsultationMapper;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.service.ConsultationService;
import com.fuyun.inpatient.vo.ConsultationVO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会诊管理域服务实现（FU-M04-09，V908 consultation 业务面）。会诊为模块内独立小状态机
 * （REQUESTED/ACCEPTED/COMPLETED/CANCELLED，不经 OrderStateMachineService）——迁移唯一经
 * ConsultationMapper CAS 条件更新 + 影响行数判定（GC23），零行定性 IP-1020。<b>超时升级为
 * 动作非状态迁移</b>：列表读路径对 REQUESTED 且越过响应截止且未标记行做惰性判定——置
 * overdue_flag（CAS 旧值限定兜底并发双读）+ 事务内发布 inpatient.consultation.overdue 动作
 * 事件一次（DB 标记防重发）+ warn 升级留痕（通知中心缺位期降级为列表标记可见——降级清单②），
 * 状态停留 REQUESTED 仍可被响应（接单清标记）。响应时限权威：URGENT +30min / NORMAL +24h
 * （急会诊时限红线，调研依据 13）。事件载荷禁患者姓名/诊断文本（GC22）。
 * 线程安全：无状态 singleton；写路径 @Transactional 收口，列表读路径为写事务（overdue 发布
 * 须经事务内 publishEvent 承载 AFTER_COMMIT——只读事务无法满足发布面）。
 */
@Slf4j
public class ConsultationServiceImpl implements ConsultationService {

    /** 无登录上下文场景的操作者回退值（读路径置标记的审计列兜底，与审计默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 会诊单号类型（InpatientSeqGate CS 段——GC15 业务号五类白名单） */
    private static final String SEQ_TYPE_CONSULT = "CS";

    /** 列表排序（申请时点升序——在途会诊先到先响应的工作列表 FIFO 口径） */
    private static final String LIST_ORDER_BY = "ORDER BY requested_at";

    private final ConsultationMapper consultationMapper;

    private final InpatientVisitMapper visitMapper;

    private final InpatientSeqGate seqGate;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 InpatientWebConfig @Import）。
     *
     * @param consultationMapper 会诊单 mapper，非空；单落库与四 CAS 面（状态机唯一执行面）
     * @param visitMapper        住院就诊 mapper，非空；就诊守卫与 I 型号出参映射
     * @param seqGate            住院业务号发号器（CS 会诊号段），非空
     * @param events             进程内事件发布器（AFTER_COMMIT 出 MQ），非空
     */
    public ConsultationServiceImpl(
            ConsultationMapper consultationMapper,
            InpatientVisitMapper visitMapper,
            InpatientSeqGate seqGate,
            ApplicationEventPublisher events) {
        this.consultationMapper = consultationMapper;
        this.visitMapper = visitMapper;
        this.seqGate = seqGate;
        this.events = events;
    }

    /**
     * 会诊申请（独立申请路径）：守卫（在院且已入科）→ 词表裁决 → 时限计算 → 落库 →
     * 事务内发布 requested（V901 id 68 载荷申请态子集）。
     */
    @Override
    @Transactional
    public ConsultationVO create(ConsultationCreateRequest req) {
        // 紧急程度词表裁决（词表外拒 IP-1022——响应时限的计算输入必须词表内）
        ConsultationUrgency urgency = ConsultationUrgency.fromCode(req.urgency());
        if (urgency == null) {
            throw paramInvalid("urgency", req.urgency());
        }
        // 会诊级别缺省科内；显式传值词表外拒 IP-1022（MDT 预留值可直接落库——P3 完整化）
        ConsultationLevel level =
                isBlank(req.level()) ? ConsultationLevel.DEPT : ConsultationLevel.fromCode(req.level());
        if (level == null) {
            throw paramInvalid("level", req.level());
        }
        InpatientVisit visit = requireVisit(req.visitId());
        // 守卫：会诊申请限在院态（未入科/已申请出院/已出院一律拒）
        if (!VisitStatus.ADMITTED.getCode().equals(visit.getStatus())) {
            throw new BizException(
                    InpatientErrorCode.VISIT_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "住院就诊状态不允许会诊申请：visitId=" + req.visitId() + "，当前状态=" + visit.getStatus());
        }
        // 申请科室权威在库（就诊当前科室——禁前端传人）；在院缺科室定性数据不一致
        String fromDeptId = visit.getCurrentDeptId();
        if (isBlank(fromDeptId)) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "在院就诊缺当前科室（数据不一致），无法定位申请科室：visitId=" + req.visitId());
        }
        String operator = OperatorContextHolder.get();
        long operatorId = parseOperatorAsEmployeeId();
        // 响应截止=申请时点+紧急程度时限（急会诊 30min 红线承载锚——读时惰性逾期判定基准）
        OffsetDateTime requestedAt = OffsetDateTime.now();
        OffsetDateTime deadline = requestedAt.plus(urgency.responseWindow());
        Consultation row = new Consultation();
        row.setConsultNo(seqGate.nextNo(SEQ_TYPE_CONSULT));
        row.setVisitId(visit.getId());
        row.setPatientId(visit.getPatientId());
        row.setFromDeptId(fromDeptId);
        row.setRequesterId(operator);
        row.setToDeptId(req.toDeptId());
        row.setLevel(level.getCode());
        row.setUrgency(urgency.getCode());
        row.setReason(req.reason());
        row.setRequestedAt(requestedAt);
        row.setResponseDeadline(deadline);
        row.setOverdueFlag(false);
        row.setStatus(ConsultationStatus.REQUESTED.getCode());
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        // 数据库写操作：会诊单落库（uk_consult_no 兜底发号唯一）
        consultationMapper.insert(row);
        // 事务内发布会诊申请事件（AFTER_COMMIT 出 fy.topic；载荷申请态子集，禁患者姓名/诊断）
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_CONSULTATION_REQUESTED,
                new ConsultationPayload(
                        row.getConsultNo(),
                        visit.getVisitId(),
                        visit.getPatientId(),
                        fromDeptId,
                        req.toDeptId(),
                        urgency.getCode(),
                        requestedAt.toInstant(),
                        deadline.toInstant(),
                        null,
                        null,
                        null,
                        null,
                        req.reason())));
        log.info(
                "会诊申请完成：consultNo={}，visitId={}，patientId={}，urgency={}，level={}，响应截止={}，operator={}（员工ID={}）",
                row.getConsultNo(),
                req.visitId(),
                visit.getPatientId(),
                urgency.getCode(),
                level.getCode(),
                deadline,
                operator,
                operatorId);
        return ConsultationVO.from(row, visit.getVisitId());
    }

    /**
     * 受邀科接单：REQUESTED→ACCEPTED CAS（库端接单时点 + 清逾期标记——逾期单仍可响应）→
     * 回读 → 发布 accepted（V901 id 69 载荷）。
     */
    @Override
    @Transactional
    public ConsultationVO accept(String consultNo) {
        Consultation row = requireConsult(consultNo);
        String operator = OperatorContextHolder.get();
        parseOperatorAsEmployeeId();
        // 状态机 CAS（0 行=非 REQUESTED 态——已接单/取消/完成，定性 IP-1020）；接单同语句清
        // overdue_flag（超时升级为动作非状态迁移——逾期不阻断响应闭环）
        if (consultationMapper.casAccept(consultNo, operator) == 0) {
            throw stateRejected(consultNo, row.getStatus(), "接单");
        }
        // 回读接单后行（接单时点由库端 now() 写入禁应用时钟——事件载荷源，出院申请回读先例）
        Consultation accepted = requireConsult(consultNo);
        String visitNo = visitNoOf(accepted.getVisitId());
        // 事务内发布会诊响应事件（V901 id 69 载荷响应态子集——追加 acceptedAt）
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_CONSULTATION_ACCEPTED,
                new ConsultationPayload(
                        accepted.getConsultNo(),
                        visitNo,
                        accepted.getPatientId(),
                        accepted.getFromDeptId(),
                        accepted.getToDeptId(),
                        accepted.getUrgency(),
                        accepted.getRequestedAt().toInstant(),
                        accepted.getResponseDeadline().toInstant(),
                        accepted.getResponseTime().toInstant(),
                        null,
                        null,
                        null,
                        accepted.getReason())));
        log.info(
                "会诊接单完成：consultNo={}，visitId={}，toDeptId={}，逾期标记已清={}，operator={}",
                consultNo,
                visitNo,
                accepted.getToDeptId(),
                accepted.getOverdueFlag(),
                operator);
        return ConsultationVO.from(accepted, visitNo);
    }

    /**
     * 会诊意见提交：ACCEPTED→COMPLETED CAS（库端完成时点 + 意见同语句归档供 M09 引用）→
     * 回读 → 发布 completed（V901 id 70 载荷）。
     */
    @Override
    @Transactional
    public ConsultationVO opinion(String consultNo, ConsultationOpinionRequest req) {
        Consultation row = requireConsult(consultNo);
        String operator = OperatorContextHolder.get();
        parseOperatorAsEmployeeId();
        // 状态机 CAS（0 行=非 ACCEPTED 态——未接单/已取消/已完成，定性 IP-1020）；意见随完成
        // 迁移同语句归档（M09 病历引用取数面）
        if (consultationMapper.casComplete(consultNo, req.opinion(), operator) == 0) {
            throw stateRejected(consultNo, row.getStatus(), "意见提交");
        }
        Consultation completed = requireConsult(consultNo);
        String visitNo = visitNoOf(completed.getVisitId());
        // 事务内发布会诊完成事件（V901 id 70 载荷完成态子集——追加 completedAt）
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_CONSULTATION_COMPLETED,
                new ConsultationPayload(
                        completed.getConsultNo(),
                        visitNo,
                        completed.getPatientId(),
                        completed.getFromDeptId(),
                        completed.getToDeptId(),
                        completed.getUrgency(),
                        completed.getRequestedAt().toInstant(),
                        completed.getResponseDeadline().toInstant(),
                        null,
                        completed.getConsultTime().toInstant(),
                        null,
                        null,
                        completed.getReason())));
        log.info("会诊意见提交完成（闭环归档）：consultNo={}，visitId={}，operator={}", consultNo, visitNo, operator);
        return ConsultationVO.from(completed, visitNo);
    }

    /**
     * 取消会诊：REQUESTED/ACCEPTED→CANCELLED CAS（双合法出边；COMPLETED 已归档意见不可取消）
     * → 发布 cancelled（V901 id 72 载荷，reason 取单面申请原因——取消无独立入参）。
     */
    @Override
    @Transactional
    public ConsultationVO cancel(String consultNo) {
        Consultation row = requireConsult(consultNo);
        String operator = OperatorContextHolder.get();
        parseOperatorAsEmployeeId();
        // 状态机 CAS（0 行=终态/并发迁移，定性 IP-1020）；取消无落库时点列（V908 字段冻结面），
        // cancelled 事件时点取应用时钟（作废事件同款先例）
        if (consultationMapper.casCancel(consultNo, operator) == 0) {
            throw stateRejected(consultNo, row.getStatus(), "取消");
        }
        String priorStatus = row.getStatus();
        row.setStatus(ConsultationStatus.CANCELLED.getCode());
        String visitNo = visitNoOf(row.getVisitId());
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_CONSULTATION_CANCELLED,
                new ConsultationPayload(
                        row.getConsultNo(),
                        visitNo,
                        row.getPatientId(),
                        row.getFromDeptId(),
                        row.getToDeptId(),
                        row.getUrgency(),
                        row.getRequestedAt().toInstant(),
                        row.getResponseDeadline().toInstant(),
                        null,
                        null,
                        null,
                        OffsetDateTime.now().toInstant(),
                        row.getReason())));
        log.info("会诊取消完成：consultNo={}，visitId={}，迁移前状态={}，operator={}", consultNo, visitNo, priorStatus, operator);
        return ConsultationVO.from(row, visitNo);
    }

    /**
     * 会诊单分页查询 + 读时惰性逾期升级：当前页 REQUESTED 且越限且未标记行逐行置标记
     * （CAS 一次）+ 发布 overdue 动作事件一次 + warn 升级留痕——写事务承载（发布面须事务内
     * publishEvent 走 AFTER_COMMIT）。
     */
    @Override
    @Transactional
    public PageResult<ConsultationVO> list(ConsultationStatus status, String deptId, int page, int size) {
        // 状态条件缺席即全状态（null.getCode() 惰性求值防护：先取值再进条件）
        String statusCode = status == null ? null : status.getCode();
        // 数据库读操作：会诊分页（科室过滤=申请/受邀任一侧命中——申请方与受邀方双视角工作列表）
        Page<Consultation> result = consultationMapper.selectPage(
                new Page<>(page + 1, size),
                Wrappers.<Consultation>lambdaQuery()
                        .eq(statusCode != null, Consultation::getStatus, statusCode)
                        .and(deptId != null && !deptId.isBlank(), w -> w.eq(Consultation::getFromDeptId, deptId)
                                .or()
                                .eq(Consultation::getToDeptId, deptId))
                        .last(LIST_ORDER_BY));
        // 就诊号批量映射（I 型号出参转写——页内一跳批量取数免行级 N+1，欠费清单同款先例）
        Map<Long, String> visitNos = visitNosOf(result.getRecords());
        escalateOverdueInPage(result.getRecords(), visitNos);
        return PageResult.of(
                result.getRecords().stream()
                        .map(row -> ConsultationVO.from(row, visitNos.get(row.getVisitId())))
                        .toList(),
                page,
                size,
                result.getTotal());
    }

    /**
     * 页内读时惰性逾期升级（动作非状态迁移——GC21④）：REQUESTED 且 now&gt;响应截止且未标记行
     * → casMarkOverdue 置位（旧值限定 CAS 兜底并发双读——仅首个置位方发布）+ 事务内发布
     * inpatient.consultation.overdue 动作事件（DB 标记防重发，二次查询零行不重发）+ warn 升级
     * 留痕（重复通知目标科室+上报医务降级为标记可见——通知中心 P3）。状态停留 REQUESTED
     * 仍可被响应。
     *
     * @param rows     当前页会诊行，非空（可为空清单）
     * @param visitNos 就诊主键→I 型号映射（事件载荷源），非空
     */
    private void escalateOverdueInPage(List<Consultation> rows, Map<Long, String> visitNos) {
        OffsetDateTime now = OffsetDateTime.now();
        String operator = operator();
        for (Consultation row : rows) {
            // 惰性判定三条件：待响应态 + 已越响应截止 + 未置标记（已标记/未越限/已流转零开销跳过）
            if (!ConsultationStatus.REQUESTED.getCode().equals(row.getStatus())
                    || !row.getResponseDeadline().isBefore(now)
                    || Boolean.TRUE.equals(row.getOverdueFlag())) {
                continue;
            }
            // 逾期标记 CAS（旧值 false 限定——并发双读窗口仅首个置位方发事件，DB 标记防重发）
            if (consultationMapper.casMarkOverdue(row.getConsultNo(), operator) == 0) {
                continue;
            }
            row.setOverdueFlag(true);
            // 事务内发布超时升级动作事件（V901 id 71 载荷超时态子集；状态停留 REQUESTED）
            events.publishEvent(new InpatientDomainEvent(
                    InpatientMessagingConstants.EVENT_CONSULTATION_OVERDUE,
                    new ConsultationPayload(
                            row.getConsultNo(),
                            visitNos.get(row.getVisitId()),
                            row.getPatientId(),
                            row.getFromDeptId(),
                            row.getToDeptId(),
                            row.getUrgency(),
                            row.getRequestedAt().toInstant(),
                            row.getResponseDeadline().toInstant(),
                            null,
                            null,
                            now.toInstant(),
                            null,
                            row.getReason())));
            // 升级动作=warn 留痕（重复通知/上报医务归通知中心 P3——降级清单②）
            log.warn(
                    "会诊响应超时升级（动作广播，状态停留 REQUESTED 仍可响应）：consultNo={}，visitId={}，toDeptId={}，响应截止={}",
                    row.getConsultNo(),
                    visitNos.get(row.getVisitId()),
                    row.getToDeptId(),
                    row.getResponseDeadline());
        }
    }

    /**
     * 就诊主键→I 型号批量映射（页内一跳批量取数；就诊行缺失兜底缺射——出参 visitId 为 null，
     * 与欠费清单床位缺失兜底同款宽容语义）。
     *
     * @param rows 当前页会诊行，非空
     * @return 主键→I 型号映射，非空（空页为空映射）
     */
    private Map<Long, String> visitNosOf(List<Consultation> rows) {
        List<Long> visitPks =
                rows.stream().map(Consultation::getVisitId).distinct().toList();
        if (visitPks.isEmpty()) {
            return Map.of();
        }
        // 数据库读操作：页内就诊行批量投影（in 批量禁行级 N+1，A.4.3-14；selectByIds 为 MP 3.5.17
        // 非过时形态——selectBatchIds 已标 deprecated）
        return visitMapper.selectByIds(visitPks).stream()
                .collect(Collectors.toMap(InpatientVisit::getId, InpatientVisit::getVisitId, (a, b) -> a));
    }

    /**
     * 按会诊单号定位行（未命中定性 IP-1019；逻辑删由 @TableLogic 自动过滤）。
     *
     * @param consultNo 会诊单号，非空
     * @return 会诊单行，非空
     * @throws BizException IP-1019 会诊单不存在时触发
     */
    private Consultation requireConsult(String consultNo) {
        Consultation row = consultationMapper.selectOne(
                Wrappers.<Consultation>lambdaQuery().eq(Consultation::getConsultNo, consultNo));
        if (row == null) {
            throw new BizException(
                    InpatientErrorCode.CONSULTATION_NOT_FOUND, HttpStatus.NOT_FOUND, "会诊单不存在：" + consultNo);
        }
        return row;
    }

    /** 按就诊号定位行（未命中定性 IP-1007；逻辑删由 @TableLogic 自动过滤）。 */
    private InpatientVisit requireVisit(String visitId) {
        InpatientVisit visit =
                visitMapper.selectOne(Wrappers.<InpatientVisit>lambdaQuery().eq(InpatientVisit::getVisitId, visitId));
        if (visit == null) {
            throw new BizException(InpatientErrorCode.VISIT_NOT_FOUND, HttpStatus.NOT_FOUND, "住院就诊不存在：" + visitId);
        }
        return visit;
    }

    /**
     * 就诊主键→I 型号映射（事件载荷号口径；未命中定性 IP-1007 数据不一致）。
     *
     * @param visitPk 住院就诊主键，非空
     * @return I 型 14 位就诊号，非空
     * @throws BizException IP-1007 就诊行缺失（数据不一致）时触发
     */
    private String visitNoOf(Long visitPk) {
        InpatientVisit visit = visitMapper.selectById(visitPk);
        if (visit == null) {
            throw new BizException(
                    InpatientErrorCode.VISIT_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "会诊关联住院就诊不存在（数据不一致）：visitId(pk)=" + visitPk);
        }
        return visit.getVisitId();
    }

    /**
     * 操作者标识解析为员工 ID（写路径口径；缺失/非数字显式 IP-1022 拒绝——W-22⑦ 同款守卫）。
     *
     * @return 员工 ID，非空
     * @throws BizException IP-1022 操作者标识缺失或非数字时触发
     */
    private static long parseOperatorAsEmployeeId() {
        String operator = OperatorContextHolder.get();
        if (operator == null || !operator.matches("\\d+")) {
            throw new BizException(
                    InpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "操作者标识缺失或非数字（无法定位会诊操作主体）：" + maskOperator(operator));
        }
        return Long.parseLong(operator);
    }

    /** 操作者取值（读路径置标记的审计留痕；无登录上下文回退 system，与审计列默认同源）。 */
    private static String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }

    /** 空串判定（null 或全空白）。 */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 工号脱敏（等保三级口径，禁明文工号出 ProblemDetail/日志）：首尾各留 1 位，中段 ***。 */
    private static String maskOperator(String operator) {
        if (operator == null || operator.length() <= 2) {
            return "***";
        }
        return operator.charAt(0) + "***" + operator.charAt(operator.length() - 1);
    }

    /** 入参格式非法（IP-1022）统一构造：词表外/结构校验不过。 */
    private static BizException paramInvalid(String field, String value) {
        return new BizException(
                InpatientErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "入参格式非法——" + field + " 词表外：" + value);
    }

    /**
     * 会诊状态机违例（IP-1020）统一构造：CAS 零行/终态再迁移。
     *
     * @param consultNo 会诊单号（拒绝文案定位），非空
     * @param current   迁移前状态快照，非空
     * @param action    动作名（接单/意见提交/取消），非空
     * @return BizException（调用方抛出）
     */
    private static BizException stateRejected(String consultNo, String current, String action) {
        return new BizException(
                InpatientErrorCode.CONSULTATION_STATE_NOT_ALLOWED,
                HttpStatus.CONFLICT,
                "会诊单状态不允许" + action + "：consultNo=" + consultNo + "，当前状态=" + current);
    }
}
