package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.context.RoleContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.api.ExecuteConfirmRequest;
import com.fuyun.inpatient.api.OrderExecutionConfirmPort;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.api.OrderExecutionCompletedPayload;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.dto.CancelExecutionRequest;
import com.fuyun.nursing.dto.CheckRequest;
import com.fuyun.nursing.dto.FinishRequest;
import com.fuyun.nursing.dto.OverrideCheckRequest;
import com.fuyun.nursing.dto.SignReceiveRequest;
import com.fuyun.nursing.dto.StartRequest;
import com.fuyun.nursing.entity.ExecutionCheckLog;
import com.fuyun.nursing.entity.InfusionMonitorLink;
import com.fuyun.nursing.entity.NursingWardConfig;
import com.fuyun.nursing.entity.OrderExecution;
import com.fuyun.nursing.enums.CheckType;
import com.fuyun.nursing.enums.ExecutionStatus;
import com.fuyun.nursing.enums.ExecutionType;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.ExecutionCheckLogMapper;
import com.fuyun.nursing.mapper.InfusionMonitorLinkMapper;
import com.fuyun.nursing.mapper.NursingWardConfigMapper;
import com.fuyun.nursing.mapper.OrderExecutionMapper;
import com.fuyun.nursing.service.IOrderExecutionOperateService;
import com.fuyun.nursing.vo.OrderExecutionTraceVO;
import com.fuyun.nursing.vo.OrderExecutionVO;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 执行单操作域服务实现（V1106 order_execution，Task 5）：五环节状态链操作 + 工作台/占用/
 * 追溯三读面 + 破码放行双授权 + 摆药签收衔接。CAS 全集经 OrderExecutionMapper 注解 SQL
 * （GC26：@Update + 影响行数判定 + 显式 deleted=0）；时区红线：业务时点一律北京钟面
 * now(HEALTHCARE_TZ)，禁裸 now()。
 *
 * <p>双路回签（GC17）：辅路径=事务内 publishEvent（NursingEventPublisher AFTER_COMMIT 出
 * MQ）；主路径=TransactionSynchronization afterCommit 钩子在 COMPLETED/CANCELLED 事务提交
 * 后进程内同步调 inpatient 回签端口（无事务环境直调——单测形态），失败置 confirm_status=
 * COMPENSATING 不抛出（床旁不阻塞），补偿扫描归 ExecutionConfirmCompensator（tick 接线
 * 归 Task 9）。回签双路仅对 m04PlanNo 非空行生效——临时单（m04_plan_no NULL）无 M04 计划
 * 对账锚（临时单次计划不经 order-plan.generated 事件下发，P1 世界由 M04 execute-confirm
 * 端点人工回签承载；差异注记见 PR 报告，M05 批量自动回签覆盖长期计划拆分行）。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class OrderExecutionOperateServiceImpl extends ServiceImpl<OrderExecutionMapper, OrderExecution>
        implements IOrderExecutionOperateService {

    /** 系统链路等无登录上下文场景的操作者回退值（与 V1106 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 回签对账状态：待对账（生成默认） */
    private static final String CONFIRM_PENDING = "PENDING";

    /** 回签对账状态：补偿中（主路径未达，经补偿端口重试） */
    private static final String CONFIRM_COMPENSATING = "COMPENSATING";

    /** 回签对账状态：已对账 */
    private static final String CONFIRM_CONFIRMED = "CONFIRMED";

    /** 执行时间窗缺省（分钟，V1107 列默认同源——配置行缺失/列空回退） */
    private static final int DEFAULT_WINDOW_MINUTES = 30;

    /** 破码放行授权角色缺省（V1107 列默认同源——护士长） */
    private static final String DEFAULT_OVERRIDE_ROLES = "HEAD_NURSE";

    /** 摆药发药类型：住院 PIVAS（静脉配置——触发 INFUSION 升格与监测建链） */
    private static final String DISPENSE_TYPE_INPATIENT_PIVA = "INPATIENT_PIVA";

    /** 白班窗口起点（班次映射与 M04 计划 shift 落班同源：08:00–16:00 DAY / 16:00–24:00 EVENING / 00:00–08:00 NIGHT） */
    private static final LocalTime SHIFT_DAY_START = LocalTime.of(8, 0);

    /** 小夜班窗口起点 */
    private static final LocalTime SHIFT_EVENING_START = LocalTime.of(16, 0);

    /** 扫码摘要脱敏保留前缀长度（前 4 后 2 明文口径） */
    private static final int DIGEST_HEAD = 4;

    /** 扫码摘要脱敏保留后缀长度 */
    private static final int DIGEST_TAIL = 2;

    /** 核对结论通过字面量（execution_check_log.check_result 值域） */
    private static final String CHECK_RESULT_PASS = "PASS";

    /** 核对结论失败字面量 */
    private static final String CHECK_RESULT_FAIL = "FAIL";

    private final ExecutionCheckLogMapper checkLogMapper;

    private final InfusionMonitorLinkMapper monitorLinkMapper;

    private final NursingWardConfigMapper wardConfigMapper;

    private final ApplicationEventPublisher events;

    private final OrderExecutionConfirmPort confirmPort;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param executionMapper  执行单 mapper，非空；ServiceImpl 基座 mapper
     * @param checkLogMapper   扫码核对流水 mapper，非空；check/override-check 落行与 trace 聚合取数面
     * @param monitorLinkMapper 输液监测挂接 mapper，非空；瓶签核对取数与 PIVAS 建链面
     * @param wardConfigMapper 病区配置 mapper，非空；时间窗/授权角色取数面
     * @param events           进程内事件发布器，非空；回执事件事务内发布（AFTER_COMMIT 出 MQ）
     * @param confirmPort      M04 回签端口，非空；主路径进程内直调（事务提交后）
     */
    public OrderExecutionOperateServiceImpl(
            OrderExecutionMapper executionMapper,
            ExecutionCheckLogMapper checkLogMapper,
            InfusionMonitorLinkMapper monitorLinkMapper,
            NursingWardConfigMapper wardConfigMapper,
            ApplicationEventPublisher events,
            OrderExecutionConfirmPort confirmPort) {
        this.checkLogMapper = checkLogMapper;
        this.monitorLinkMapper = monitorLinkMapper;
        this.wardConfigMapper = wardConfigMapper;
        this.events = events;
        this.confirmPort = confirmPort;
    }

    /**
     * 执行工作台分组清单：守卫链见接口注。shift 过滤经计划时间窗口组合（同日内三班次
     * 全覆盖无重叠），SQL 边界承载保分页口径正确（内存过滤会破坏 total）。
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<OrderExecutionVO> listWorkbench(
            String wardId, LocalDate date, String shift, String status, int page, int size) {
        if (wardId == null || wardId.isBlank()) {
            throw new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "病区编码必填（wardId）");
        }
        // 班次 code 显式格式校验（W-22⑦ 禁裸值——词表外值显式 NS-1019）
        if (shift != null
                && !shift.isBlank()
                && !Set.of("DAY", "EVENING", "NIGHT").contains(shift)) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "班次 code 非法（DAY/EVENING/NIGHT）：" + shift);
        }
        // 状态 code 显式格式校验（ExecutionStatus 词表）
        ExecutionStatus statusEnum = status == null || status.isBlank() ? null : ExecutionStatus.fromCode(status);
        if (status != null && !status.isBlank() && statusEnum == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "执行单状态 code 非法：" + status);
        }
        // 日期缺省北京钟面当日（时区红线：禁容器时区漂移业务日）
        LocalDate planDate = date == null ? LocalDate.now(TimeConstants.HEALTHCARE_TZ) : date;
        ZoneOffset offset = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ).getOffset();
        OffsetDateTime windowStart = planDate.atStartOfDay().atOffset(offset);
        OffsetDateTime windowEnd = planDate.plusDays(1).atStartOfDay().atOffset(offset);
        // 班次窗口收窄（与 M04 计划 shift 落班窗口同源：DAY 08–16 / EVENING 16–24 / NIGHT 0–8；
        // EVENING 终点 00:00 为次日零点——终点不晚于起点时跨日收口，否则空窗漏单）
        if (shift != null && !shift.isBlank()) {
            LocalTime start = shiftStart(shift);
            LocalTime end = shiftEnd(shift);
            windowStart = planDate.atTime(start).atOffset(offset);
            windowEnd = (end.isAfter(start) ? planDate : planDate.plusDays(1))
                    .atTime(end)
                    .atOffset(offset);
        }
        // 数据库读操作：病区+窗口+可选状态分页查询（计划时间升序）
        String statusCode = statusEnum == null ? null : statusEnum.getCode();
        Page<OrderExecution> result = this.lambdaQuery()
                .eq(OrderExecution::getWardId, wardId)
                .ge(OrderExecution::getPlanTime, windowStart)
                .lt(OrderExecution::getPlanTime, windowEnd)
                .eq(statusEnum != null, OrderExecution::getStatus, statusCode)
                .orderByAsc(OrderExecution::getPlanTime)
                .page(new Page<>(page, size));
        List<OrderExecutionVO> content =
                result.getRecords().stream().map(OrderExecutionVO::from).toList();
        return PageResult.of(content, page, size, result.getTotal());
    }

    /** 人工补签收：CREATED→SIGNED 单行 CAS；药品类自动签收主入口归摆药签收衔接。 */
    @Override
    @Transactional
    public OrderExecutionVO signReceive(String executionNo, SignReceiveRequest req) {
        OrderExecution row = requireByNo(executionNo);
        OffsetDateTime signedAt = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ);
        long executorId = contextOperatorId();
        String operator = operator();
        // 数据库写操作：补签收 CAS（CREATED 限定；0 行=已签收/已核对/终态）
        if (baseMapper.casSignReceive(executionNo, signedAt, executorId, operator) == 0) {
            throw stateNotAllowed(executionNo, row.getStatus(), "签收");
        }
        log.info(
                "执行单人工补签收：executionNo={}，executorId={}，receivedNote={}，operator={}",
                executionNo,
                executorId,
                req.receivedNote(),
                operator);
        row.setStatus(ExecutionStatus.SIGNED.getCode());
        row.setSignedAt(signedAt);
        row.setExecutorId(executorId);
        return OrderExecutionVO.from(row);
    }

    /**
     * 三向扫码核对：单维核对（codeType 承载）→ 流水落行（PASS/FAIL 双落）→ PASS 落
     * CHECKED CAS / FAIL NS-1022 拒绝不迁移。
     */
    @Override
    @Transactional
    public OrderExecutionVO check(String executionNo, CheckRequest req) {
        OrderExecution row = requireByNo(executionNo);
        // 词表收口（NS-1019——禁词表外值入库）
        CheckType type = CheckType.fromCode(req.codeType());
        long checkerId = contextOperatorId();
        OffsetDateTime occurredAt = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ);
        // 核对维度裁决（dispatch §3 冻结）：腕带=visitId / 瓶签=挂接袋签码 / 执行单=路径执行单号
        boolean pass =
                switch (type) {
                    case WRISTBAND ->
                        row.getVisitId() != null && row.getVisitId().equals(req.code());
                    case BAG_LABEL ->
                        matchMonitorLink(executionNo, link -> Objects.equals(link.getBagLabelCode(), req.code()));
                    case DEVICE -> executionNo.equals(req.code());
                    case OVERRIDE -> false;
                };
        // 数据库写操作：核对流水只增落行（PASS/FAIL 双落——审计底座，条码原文脱敏禁全文）
        checkLogMapper.insert(checkLogRow(executionNo, type, pass, req.code(), checkerId, occurredAt));
        if (!pass) {
            log.warn(
                    "执行单三向核对失败：executionNo={}，checkType={}，failType={}，checkerId={}",
                    executionNo,
                    type.getCode(),
                    type.failType(),
                    checkerId);
            throw new BizException(
                    NursingErrorCode.EXECUTION_CHECK_FAILED,
                    HttpStatus.CONFLICT,
                    "扫码核对不匹配（" + type.getCode() + "，fail_type=" + type.failType() + "）：executionNo=" + executionNo);
        }
        // 数据库写操作：核对通过 CAS（CREATED/SIGNED→CHECKED；0 行=已核对/执行中/终态）
        if (baseMapper.casCheckPassed(executionNo, occurredAt, checkerId, operator()) == 0) {
            throw stateNotAllowed(executionNo, row.getStatus(), "核对通过迁移");
        }
        log.info("执行单扫码核对通过：executionNo={}，checkType={}，checkerId={}", executionNo, type.getCode(), checkerId);
        row.setStatus(ExecutionStatus.CHECKED.getCode());
        row.setCheckedAt(occurredAt);
        row.setCheckerId(checkerId);
        return OrderExecutionVO.from(row);
    }

    /**
     * 开始执行：时间窗校验（窗外未破码 NS-1027）→ CHECKED→EXECUTING CAS。
     * INFUSION 建链/激活与 infusion.started 事件归 Task 6 在本 CAS 成功后段扩展
     * （扩展点=下方 CAS 与日志之间，InfusionService.startInfusion 注入位）。
     */
    @Override
    @Transactional
    public OrderExecutionVO start(String executionNo, StartRequest req) {
        OrderExecution row = requireByNo(executionNo);
        OffsetDateTime now = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ);
        // 时间窗校验：破码三源合流（行 override_flag / 请求 overrideTimeWindow——口头医嘱现场确认面）
        boolean override = Boolean.TRUE.equals(row.getOverrideFlag()) || Boolean.TRUE.equals(req.overrideTimeWindow());
        int windowMinutes = windowMinutesOf(row.getWardId());
        if (!override && outsideWindow(row.getPlanTime(), windowMinutes, now)) {
            log.warn(
                    "执行单计划时间窗外开始被拒：executionNo={}，planTime={}，windowMinutes={}，operator={}",
                    executionNo,
                    row.getPlanTime(),
                    windowMinutes,
                    operator());
            throw new BizException(
                    NursingErrorCode.EXECUTION_TIME_WINDOW,
                    HttpStatus.CONFLICT,
                    "计划时间窗外（±" + windowMinutes + " 分钟）禁止开始执行，需破码放行：executionNo=" + executionNo);
        }
        // 数据库写操作：开始执行 CAS（CHECKED 限定；0 行=未核对/执行中/终态）
        if (baseMapper.casStart(executionNo, now, req.executorId(), operator()) == 0) {
            throw stateNotAllowed(executionNo, row.getStatus(), "开始执行");
        }
        log.info(
                "执行单开始执行：executionNo={}，executorId={}，executionType={}，deviceId={}，override={}",
                executionNo,
                req.executorId(),
                row.getExecutionType(),
                req.deviceId(),
                override);
        row.setStatus(ExecutionStatus.EXECUTING.getCode());
        row.setStartedAt(now);
        row.setExecutorId(req.executorId());
        return OrderExecutionVO.from(row);
    }

    /**
     * 执行完成：EXECUTING→COMPLETED CAS + 双路回签（辅路径事务内发布 + 主路径事务提交后
     * 进程内回签——失败置 COMPENSATING 不抛出）。
     */
    @Override
    @Transactional
    public OrderExecutionVO finish(String executionNo, FinishRequest req) {
        OrderExecution row = requireByNo(executionNo);
        OffsetDateTime finishedAt = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ);
        // 数据库写操作：完成 CAS（EXECUTING 限定；0 行=未开始/终态）
        if (baseMapper.casFinish(executionNo, finishedAt, operator()) == 0) {
            throw stateNotAllowed(executionNo, row.getStatus(), "完成");
        }
        row.setStatus(ExecutionStatus.COMPLETED.getCode());
        row.setFinishedAt(finishedAt);
        // 辅路径：回执事件事务内发布（AFTER_COMMIT 出 MQ——仅长期计划拆分行有对账锚）
        publishCompletedReceipt(row, req.executorId());
        // 主路径：事务提交后进程内回签（无事务环境直调——单测形态）
        registerConfirmAfterCommit(row, req.executorId(), req.routeCheckResult());
        log.info("执行单完成（双路回签编排）：executionNo={}，executorId={}", executionNo, req.executorId());
        return OrderExecutionVO.from(row);
    }

    /**
     * 执行单撤销两分支：未执行三态常规撤销；EXECUTING 仅 INFUSION 型输注中断（近似权限
     * 校验前置），中断即按部分执行回签（双路，M04 计划不留悬空 PENDING）。
     */
    @Override
    @Transactional
    public OrderExecutionVO cancel(String executionNo, CancelExecutionRequest req) {
        OrderExecution row = requireByNo(executionNo);
        String operator = operator();
        if (ExecutionStatus.EXECUTING.getCode().equals(row.getStatus())) {
            // 输注中断特殊面：仅 INFUSION 型 + 护士长权限近似（角色 ∈ override_roles，RBAC 归 PR-4 W-37）
            if (!ExecutionType.INFUSION.getCode().equals(row.getExecutionType())) {
                throw stateNotAllowed(executionNo, row.getStatus(), "撤销（GENERIC 型执行中不可撤销）");
            }
            requireOverrideRole(row.getWardId(), "输注中断撤销");
            OffsetDateTime interruptedAt = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ);
            // 数据库写操作：输注中断撤销 CAS（EXECUTING+INFUSION 限定，finished_at 落链路终止时点）
            if (baseMapper.casCancelInfusionInterrupt(executionNo, req.reason(), interruptedAt, operator) == 0) {
                throw stateNotAllowed(executionNo, row.getStatus(), "输注中断撤销");
            }
            row.setStatus(ExecutionStatus.CANCELLED.getCode());
            row.setCancelReason(req.reason());
            row.setFinishedAt(interruptedAt);
            // 部分执行回签（双路）：实际入量经 routeCheckResult 留痕（M04 侧无入量列，文本承载）
            String volumeNote =
                    req.actualVolumeMl() == null ? "输注中断部分执行（未报实际入量）" : "输注中断部分执行，实际入量=" + req.actualVolumeMl() + "ml";
            publishCompletedReceipt(row, row.getExecutorId());
            registerConfirmAfterCommit(row, row.getExecutorId(), volumeNote);
            log.info(
                    "执行单输注中断撤销（部分执行回签编排）：executionNo={}，reason={}，actualVolumeMl={}，operator={}",
                    executionNo,
                    req.reason(),
                    req.actualVolumeMl(),
                    operator);
            return OrderExecutionVO.from(row);
        }
        // 数据库写操作：未执行三态常规撤销 CAS（EXECUTING 不在谓词内）
        if (baseMapper.casCancelPending(executionNo, req.reason(), operator) == 0) {
            throw stateNotAllowed(executionNo, row.getStatus(), "撤销");
        }
        log.info("执行单未执行撤销：executionNo={}，reason={}，operator={}", executionNo, req.reason(), operator);
        row.setStatus(ExecutionStatus.CANCELLED.getCode());
        row.setCancelReason(req.reason());
        return OrderExecutionVO.from(row);
    }

    /** 在途占用清单（M13 退费前置校验）：非终态行集，患者/医嘱至少一键。 */
    @Override
    @Transactional(readOnly = true)
    public List<OrderExecutionVO> occupancy(Long patientId, String m04OrderNo) {
        if (patientId == null && (m04OrderNo == null || m04OrderNo.isBlank())) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "患者 ID 与医嘱号至少携带一项（patientId/m04OrderNo）");
        }
        // 数据库读操作：在途行集（非终态=CREATED/SIGNED/CHECKED/EXECUTING，计划时间升序）
        return this.lambdaQuery()
                .eq(patientId != null, OrderExecution::getPatientId, patientId)
                .eq(m04OrderNo != null && !m04OrderNo.isBlank(), OrderExecution::getM04OrderNo, m04OrderNo)
                .notIn(
                        OrderExecution::getStatus,
                        ExecutionStatus.COMPLETED.getCode(),
                        ExecutionStatus.CANCELLED.getCode())
                .orderByAsc(OrderExecution::getPlanTime)
                .list()
                .stream()
                .map(OrderExecutionVO::from)
                .toList();
    }

    /** 单条执行单闭环追溯：五环节时点 + 核对流水升序清单 + 关联告警号一屏聚合。 */
    @Override
    @Transactional(readOnly = true)
    public OrderExecutionTraceVO trace(String executionNo) {
        OrderExecution row = requireByNo(executionNo);
        // 数据库读操作：核对流水清单（occurred_at 升序稳定排序——同刻保持落行序）
        List<OrderExecutionTraceVO.CheckLogVO> checkLogs = checkLogMapper
                .selectList(Wrappers.<ExecutionCheckLog>lambdaQuery()
                        .eq(ExecutionCheckLog::getExecutionNo, executionNo)
                        .orderByAsc(ExecutionCheckLog::getOccurredAt)
                        .orderByAsc(ExecutionCheckLog::getId))
                .stream()
                .map(OrderExecutionTraceVO.CheckLogVO::from)
                .toList();
        return OrderExecutionTraceVO.of(row, checkLogs);
    }

    /**
     * 破码放行双授权：两人不同 + 操作者角色 ∈ override_roles（第二授权人角色面无 system
     * 查询 api——当前操作者角色近似 + 审计留痕降级注记，RBAC 完整面归 PR-4 W-37）→
     * override_flag 置位 + OVERRIDE 流水落行。
     */
    @Override
    @Transactional
    public OrderExecutionVO overrideCheck(OverrideCheckRequest req) {
        OrderExecution row = requireByNo(req.executionNo());
        if (Objects.equals(req.primaryAuthorizerId(), req.secondaryAuthorizerId())) {
            throw new BizException(
                    NursingErrorCode.OVERRIDE_CHECK_INVALID,
                    HttpStatus.CONFLICT,
                    "破码放行双授权两人不得相同：executionNo=" + req.executionNo());
        }
        // 角色近似校验：当前操作者（在场授权人）角色 ∈ override_roles；第二授权人以双人+审计留痕承载
        requireOverrideRole(row.getWardId(), "破码放行");
        OffsetDateTime occurredAt = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ);
        // 数据库写操作：破码放行置位 CAS（false→true；0 行=已放行幂等，不构成失败）
        int marked = baseMapper.casMarkOverride(req.executionNo(), operator());
        // 数据库写操作：放行流水落行（operator_id=主授权人，code_digest=放行理由脱敏摘要）
        checkLogMapper.insert(checkLogRow(
                req.executionNo(), CheckType.OVERRIDE, true, req.reason(), req.primaryAuthorizerId(), occurredAt));
        log.info(
                "执行单破码放行：executionNo={}，primaryAuthorizerId={}，secondaryAuthorizerId={}，重复置位={}，operator={}",
                req.executionNo(),
                req.primaryAuthorizerId(),
                req.secondaryAuthorizerId(),
                marked == 0,
                operator());
        row.setOverrideFlag(true);
        return OrderExecutionVO.from(row);
    }

    /**
     * 摆药签收衔接（DispenseSignoffListener 消费体）：药品执行单批量 SIGNED → PIVAS
     * 升格 INFUSION + 监测建链（幂等三层：CREATED/GENERIC 谓词 + 挂接唯一索引吞并）。
     */
    @Override
    @Transactional
    public void onDispenseCompleted(
            String m04OrderNo, String dispenseType, String dispensePlanNo, String bagLabelCode) {
        String operator = operator();
        OffsetDateTime signedAt = OffsetDateTime.now(TimeConstants.HEALTHCARE_TZ);
        // 数据库写操作：药品执行单批量签收 CAS（dispensePlanNo 引用经日志承载——V1106 无承载列）
        int signed = baseMapper.casSignReceiveBatchByOrder(m04OrderNo, signedAt, operator);
        log.info(
                "摆药签收衔接批量签收：m04OrderNo={}，dispensePlanNo={}，签收行数={}，dispenseType={}，operator={}",
                m04OrderNo,
                dispensePlanNo,
                signed,
                dispenseType,
                operator);
        if (!DISPENSE_TYPE_INPATIENT_PIVA.equals(dispenseType)) {
            return;
        }
        // PIVAS 升格：已签收 GENERIC 批量升 INFUSION（M04 载荷无用法字段——静脉判定锚归摆药面）
        int upgraded = baseMapper.casUpgradeInfusionByOrder(m04OrderNo, operator);
        // 升格行全集（含此前已升格行——重复投递合流至同一批建链，幂等由挂接唯一索引承载）
        List<OrderExecution> infusionRows = this.lambdaQuery()
                .eq(OrderExecution::getM04OrderNo, m04OrderNo)
                .eq(OrderExecution::getExecutionType, ExecutionType.INFUSION.getCode())
                .list();
        int linked = 0;
        for (OrderExecution infusion : infusionRows) {
            InfusionMonitorLink link = new InfusionMonitorLink();
            link.setExecutionNo(infusion.getExecutionNo());
            link.setBagLabelCode(bagLabelCode);
            link.setLinkStatus("MONITORING");
            link.setStartedAt(signedAt);
            link.setCreatedBy(operator);
            link.setUpdatedBy(operator);
            // 数据库写操作：监测挂接幂等建链（撞 uk_monitor_link_execution DO NOTHING——重复投递零副作用）
            linked += monitorLinkMapper.insertIgnoreExecutionConflict(link);
        }
        log.info(
                "PIVAS 升格建链：m04OrderNo={}，升格行数={}，升格行全集={}，新建挂接={}，bagLabelCode={}，operator={}",
                m04OrderNo,
                upgraded,
                infusionRows.size(),
                linked,
                bagLabelCode,
                operator);
    }

    // ===================== 双路回签编排 =====================

    /**
     * 辅路径：回执事件事务内发布（NursingEventPublisher AFTER_COMMIT 出 MQ）。仅长期计划
     * 拆分行（m04PlanNo 非空）发布——id 64 载荷契约 m04PlanNo 标注非空，临时单无对账锚
     * 不发（差异注记见类注释）。
     *
     * @param row        终态迁移后执行单行（时点集已内存同步），非空
     * @param executorId 回执载荷执行护士（finish 请求承载/中断取行执行护士），非空
     */
    private void publishCompletedReceipt(OrderExecution row, Long executorId) {
        if (row.getM04PlanNo() == null) {
            log.info("执行回执事件跳过（临时单无计划对账锚）：executionNo={}，m04OrderNo={}", row.getExecutionNo(), row.getM04OrderNo());
            return;
        }
        events.publishEvent(new NursingDomainEvent(
                NursingMessagingConstants.EVENT_ORDER_EXECUTION_COMPLETED,
                new OrderExecutionCompletedPayload(
                        row.getExecutionNo(),
                        row.getM04PlanNo(),
                        row.getM04OrderNo(),
                        row.getPatientId(),
                        row.getVisitId(),
                        toInstant(row.getSignedAt()),
                        toInstant(row.getCheckedAt()),
                        toInstant(row.getStartedAt()),
                        toInstant(row.getFinishedAt()),
                        executorId == null ? 0L : executorId,
                        Boolean.TRUE.equals(row.getOverrideFlag()))));
    }

    /**
     * 主路径：回签端口调用编排（GC17 时序选型——TransactionSynchronization afterCommit）。
     * 事务提交后进程内同步调 inpatient executeConfirm（无事务环境直调，单测形态）；失败置
     * confirm_status=COMPENSATING 不抛出（床旁不阻塞），成功置 CONFIRMED。
     *
     * @param row              终态迁移后执行单行，非空
     * @param executorId       回签执行护士（计划行 executor_id 落值），非空
     * @param routeCheckResult 给药途径核对结论/中断入量留痕（可空），可空
     */
    private void registerConfirmAfterCommit(OrderExecution row, Long executorId, String routeCheckResult) {
        if (row.getM04PlanNo() == null) {
            log.info("主路径回签跳过（临时单无计划对账锚）：executionNo={}，m04OrderNo={}", row.getExecutionNo(), row.getM04OrderNo());
            return;
        }
        String executionNo = row.getExecutionNo();
        String planNo = row.getM04PlanNo();
        // 回签请求时点承载：executedAt=执行单终态时点（OffsetDateTime 直传——inpatient 侧
        // 契约类型即 OffsetDateTime，禁 Instant 中转；routeCheckResult 透传护士核对留痕）
        ExecuteConfirmRequest request = new ExecuteConfirmRequest(executorId, row.getFinishedAt(), routeCheckResult);
        Runnable confirmAction = () -> confirmQuietly(executionNo, planNo, request);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务内编排：提交后触发（回签与业务事务解耦——回签失败不回滚床旁终态）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    confirmAction.run();
                }
            });
        } else {
            confirmAction.run();
        }
    }

    /**
     * 回签端口静默调用：成功置 CONFIRMED；任一异常置 COMPENSATING 不抛出（补偿扫描归
     * ExecutionConfirmCompensator，tick 接线归 Task 9）。
     *
     * @param executionNo 执行单号（对账状态回写定位键），非空
     * @param planNo      M04 计划号（回签定位键），非空
     * @param request     回签请求（executorId/executedAt/routeCheckResult），非空
     */
    private void confirmQuietly(String executionNo, String planNo, ExecuteConfirmRequest request) {
        String operator = operator();
        try {
            confirmPort.executeConfirm(planNo, request);
            // 数据库写操作：对账状态 PENDING→CONFIRMED（0 行=并发他方已迁移，幂等留痕）
            int marked = baseMapper.casMarkConfirmStatus(executionNo, CONFIRM_PENDING, CONFIRM_CONFIRMED, operator);
            log.info("执行回签主路径成功：executionNo={}，planNo={}，confirmStatus 置位={}", executionNo, planNo, marked);
        } catch (Exception e) {
            // 主路径未达：置补偿态不抛出——床旁操作与回签对账解耦（失败仅日志+补偿面承载）
            // 数据库写操作：对账状态 PENDING→COMPENSATING
            baseMapper.casMarkConfirmStatus(executionNo, CONFIRM_PENDING, CONFIRM_COMPENSATING, operator);
            log.error(
                    "执行回签主路径失败（置 COMPENSATING 待补偿）：executionNo={}，planNo={}，原因={}",
                    executionNo,
                    planNo,
                    e.getMessage(),
                    e);
        }
    }

    // ===================== 取数与守卫辅助 =====================

    /**
     * 按执行单号定位行（uk_execution_no；未命中 NS-1020 fail-closed）。
     *
     * @param executionNo 执行单号，非空
     * @return 执行单行，非空
     */
    private OrderExecution requireByNo(String executionNo) {
        OrderExecution row = this.lambdaQuery()
                .eq(OrderExecution::getExecutionNo, executionNo)
                .one();
        if (row == null) {
            throw new BizException(NursingErrorCode.EXECUTION_NOT_FOUND, HttpStatus.NOT_FOUND, "执行单不存在：" + executionNo);
        }
        return row;
    }

    /**
     * 监测挂接匹配判定（瓶签/设备维共用）：挂接缺行或谓词不成立均失配。
     *
     * @param executionNo 执行单号（挂接定位键），非空
     * @param matcher     维度匹配谓词，非空
     * @return true=挂接在册且维度匹配
     */
    private boolean matchMonitorLink(String executionNo, java.util.function.Predicate<InfusionMonitorLink> matcher) {
        InfusionMonitorLink link = monitorLinkMapper.selectOne(
                Wrappers.<InfusionMonitorLink>lambdaQuery().eq(InfusionMonitorLink::getExecutionNo, executionNo));
        return link != null && matcher.test(link);
    }

    /**
     * 核对流水落行构造（PASS/FAIL 双面共用；条码/理由脱敏摘要承载）。
     *
     * @param executionNo 执行单号，非空
     * @param type        核对方式（枚举直取 code 与失败判定词——词表外值已在前置收口），非空
     * @param pass        核对结论（true=PASS）
     * @param rawCode     扫码原文/放行理由（脱敏前），非空
     * @param operatorId  核对护士/主授权人，非空
     * @param occurredAt  核对发生时点（北京钟面），非空
     * @return 待落库流水行，非空
     */
    private ExecutionCheckLog checkLogRow(
            String executionNo,
            CheckType type,
            boolean pass,
            String rawCode,
            long operatorId,
            OffsetDateTime occurredAt) {
        String operator = operator();
        ExecutionCheckLog logRow = new ExecutionCheckLog();
        logRow.setExecutionNo(executionNo);
        logRow.setCheckType(type.getCode());
        logRow.setCheckResult(pass ? CHECK_RESULT_PASS : CHECK_RESULT_FAIL);
        // OVERRIDE 维度无失败判定词——OTHER 承载（V1106 五词表兜底位）
        logRow.setFailType(pass ? null : type.failType() == null ? "OTHER" : type.failType());
        // 脱敏红线：前 4 后 2 明文+总长度（禁全文——条码原文属可回放敏感面）
        logRow.setCodeDigest(digest(rawCode));
        logRow.setOperatorId(operatorId);
        logRow.setOccurredAt(occurredAt);
        logRow.setCreatedBy(operator);
        logRow.setUpdatedBy(operator);
        return logRow;
    }

    /**
     * 条码/理由脱敏摘要：前 4 后 2 明文+总长度；长度 ≤6 全遮蔽（防短码全露）。
     *
     * @param raw 原文，非空
     * @return 脱敏摘要（≤64 列宽内），非空
     */
    private static String digest(String raw) {
        if (raw.length() <= DIGEST_HEAD + DIGEST_TAIL) {
            return "****(len=" + raw.length() + ")";
        }
        return raw.substring(0, DIGEST_HEAD)
                + ".."
                + raw.substring(raw.length() - DIGEST_TAIL)
                + "(len="
                + raw.length()
                + ")";
    }

    /**
     * 病区执行时间窗取数（配置行缺失/列空回退缺省 30 分钟——V1107 列默认同源）。
     *
     * @param wardId 病区编码，非空
     * @return 时间窗分钟数，正数
     */
    private int windowMinutesOf(String wardId) {
        NursingWardConfig config = wardConfigMapper.selectOne(
                Wrappers.<NursingWardConfig>lambdaQuery().eq(NursingWardConfig::getWardId, wardId));
        if (config == null || config.getExecuteTimeWindowMinutes() == null) {
            return DEFAULT_WINDOW_MINUTES;
        }
        return config.getExecuteTimeWindowMinutes();
    }

    /**
     * 破码放行/输注中断近似权限守卫：当前操作者角色 ∈ 病区 override_roles（逗号分隔角色码，
     * 缺省 HEAD_NURSE）；不符 NS-1023（RBAC 完整面归 PR-4 W-37，本 PR 以配置近似+审计留痕）。
     *
     * @param wardId 病区编码（配置取数键），非空
     * @param action 权限面语义（日志留痕），非空
     */
    private void requireOverrideRole(String wardId, String action) {
        Set<String> allowed = overrideRolesOf(wardId);
        boolean hit = RoleContextHolder.get().stream().anyMatch(allowed::contains);
        if (!hit) {
            log.warn("近似权限守卫拒绝：wardId={}，action={}，allowedRoles={}，operator={}", wardId, action, allowed, operator());
            throw new BizException(
                    NursingErrorCode.OVERRIDE_CHECK_INVALID,
                    HttpStatus.CONFLICT,
                    action + "权限不符（角色不在 override_roles）：wardId=" + wardId);
        }
    }

    /**
     * 病区破码放行授权角色解析（配置行缺失/列空回退缺省 HEAD_NURSE；逗号分隔裁剪）。
     *
     * @param wardId 病区编码，非空
     * @return 授权角色码集，非空
     */
    private Set<String> overrideRolesOf(String wardId) {
        NursingWardConfig config = wardConfigMapper.selectOne(
                Wrappers.<NursingWardConfig>lambdaQuery().eq(NursingWardConfig::getWardId, wardId));
        String rolesText = config == null
                        || config.getOverrideRoles() == null
                        || config.getOverrideRoles().isBlank()
                ? DEFAULT_OVERRIDE_ROLES
                : config.getOverrideRoles();
        return Arrays.stream(rolesText.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * 时间窗外判定：|now − planTime| 超窗即窗外（早到/迟到同拦——补签场景走显式 override）。
     *
     * @param planTime      计划执行时间，非空
     * @param windowMinutes 时间窗分钟数，正数
     * @param now           当前北京钟面时点，非空
     * @return true=窗外
     */
    private static boolean outsideWindow(OffsetDateTime planTime, int windowMinutes, OffsetDateTime now) {
        return Math.abs(Duration.between(planTime, now).toMinutes()) > windowMinutes;
    }

    /** 班次窗口起点（与 M04 计划 shift 落班同源：08:00/16:00/00:00）。 */
    private static LocalTime shiftStart(String shift) {
        return "DAY".equals(shift)
                ? SHIFT_DAY_START
                : "EVENING".equals(shift) ? SHIFT_EVENING_START : LocalTime.MIDNIGHT;
    }

    /** 班次窗口终点（左闭右开：DAY→16:00/EVENING→次日 00:00/NIGHT→08:00）。 */
    private static LocalTime shiftEnd(String shift) {
        return "DAY".equals(shift)
                ? SHIFT_EVENING_START
                : "EVENING".equals(shift) ? LocalTime.MIDNIGHT : SHIFT_DAY_START;
    }

    /**
     * 操作者上下文解析为员工 ID（核对/签收动作主体落值）：缺失/非数字 NS-1019 拒绝
     * （REST 链路操作者=登录护士——fail-closed，与 inpatient IP-1022 同款守卫语义）。
     */
    private static long contextOperatorId() {
        String operator = OperatorContextHolder.get();
        if (operator == null || !operator.matches("\\d+")) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "操作者标识缺失或非数字（无法定位核对/签收主体）");
        }
        return Long.parseLong(operator);
    }

    /** 操作者取值（无登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }

    /** OffsetDateTime→Instant（可空透传——环节时点未落值组件以 null 承载）。 */
    private static Instant toInstant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    /** 状态迁移违例异常构造（NS-1021——含当前态与动作语义）。 */
    private static BizException stateNotAllowed(String executionNo, String status, String action) {
        return new BizException(
                NursingErrorCode.EXECUTION_STATE_NOT_ALLOWED,
                HttpStatus.CONFLICT,
                "执行单状态不允许该操作（" + action + "）：executionNo=" + executionNo + "，当前状态=" + status);
    }
}
