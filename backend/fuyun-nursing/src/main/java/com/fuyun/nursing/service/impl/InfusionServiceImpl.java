package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.InfusionStartedPayload;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.entity.InfusionMonitorLink;
import com.fuyun.nursing.entity.OrderExecution;
import com.fuyun.nursing.enums.ExecutionStatus;
import com.fuyun.nursing.enums.ExecutionType;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.InfusionMonitorLinkMapper;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.mapper.OrderExecutionMapper;
import com.fuyun.nursing.service.IInfusionService;
import com.fuyun.nursing.vo.ActiveInfusionVO;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 输液闭环域服务实现（V1106，Task 6 / FU-M05-06）：开始输注挂接（激活+事件）、在途清单、
 * IoT 告警升级挂单三路。patientId 直配零新表裁决落地——告警↔执行单关联不落映射表（nursing
 * 侧按告警载荷 patientId 匹配在途行；escalated/closed 载荷无 patientId，按 latest_alarm_no
 * 反查 triggered 路已落挂接锚）。拔针全链归 {@link OrderExecutionOperateServiceImpl}（finish
 * 族终态操作复用双路回签编排，禁跨服务环依赖）。
 *
 * <p>WS 强提醒推送面（实测结论）：PR-2 既有 WS 基建仅 iot /ws/iot 与 outpatient /ws/queue
 * 两处（模块私有 broker），nursing 无 SimpMessagingTemplate 通道——board 主题
 * /topic/nursing/board/{wardId} 归 Task 11 NursingWebSocketConfig 建。本实现以 warn 级
 * 强提醒日志承载升级触达（wardId/patientId/alarmNo 全锚），Task 11 板推送接线点为本类
 * 升级方法（届时注入 SimpMessagingTemplate 补 INFUSION_ESCALATION 推送）。
 *
 * <p>线程安全：无状态 singleton；写方法 @Transactional 收口。
 */
@Slf4j
public class InfusionServiceImpl implements IInfusionService {

    /** 挂接状态词表字面量（V1106 link_status 值域；无独立枚举——列值冻结短词表） */
    private static final String LINK_STATUS_MONITORING = "MONITORING";

    /** 系统链路等无登录上下文场景的操作者回退值（与 V1106 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    private final OrderExecutionMapper executionMapper;

    private final InfusionMonitorLinkMapper monitorLinkMapper;

    private final NursingTaskMapper taskMapper;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param executionMapper  执行单 mapper，非空；在途行直配/反查定位面
     * @param monitorLinkMapper 监测挂接 mapper，非空；激活/挂单锚/复位 CAS 面
     * @param taskMapper       护理任务 mapper，非空；挂接任务优先级上调 CAS 面
     * @param events           进程内事件发布器，非空；infusion.started 事务内发布
     */
    public InfusionServiceImpl(
            OrderExecutionMapper executionMapper,
            InfusionMonitorLinkMapper monitorLinkMapper,
            NursingTaskMapper taskMapper,
            ApplicationEventPublisher events) {
        this.executionMapper = executionMapper;
        this.monitorLinkMapper = monitorLinkMapper;
        this.taskMapper = taskMapper;
        this.events = events;
    }

    /**
     * 开始输注挂接：挂接行定位（缺行 NS-1024 fail-closed——建链唯一正规入口=摆药签收 PIVAS
     * 升格，bag_label_code NOT NULL 承载，普通链路不可绕过）→ 激活 CAS（started_at 刷新+
     * iot_device_id 回填；非 MONITORING NS-1024）→ 事务内发布 infusion.started（调用方
     * start 端点事务内，AFTER_COMMIT 出 MQ）。
     */
    @Override
    @Transactional
    public void startInfusion(OrderExecution execution, String deviceId) {
        String executionNo = execution.getExecutionNo();
        InfusionMonitorLink link = monitorLinkMapper.selectOne(
                Wrappers.<InfusionMonitorLink>lambdaQuery().eq(InfusionMonitorLink::getExecutionNo, executionNo));
        if (link == null) {
            // 挂接缺行=链路断裂（INFUSION 型唯一正规建链入口为摆药签收 PIVAS 升格）：宁拒不静默
            log.warn("开始输注挂接拒绝（无监测挂接行）：executionNo={}，type={}", executionNo, execution.getExecutionType());
            throw new BizException(
                    NursingErrorCode.INFUSION_NOT_ACTIVE,
                    HttpStatus.CONFLICT,
                    "输液执行单无在途输注监测挂接（建链归摆药签收链）：executionNo=" + executionNo);
        }
        String operator = operator();
        // 数据库写操作：挂接激活 CAS（MONITORING 限定；0 行=已收口/已释放）
        if (monitorLinkMapper.casActivate(executionNo, execution.getStartedAt(), deviceId, operator) == 0) {
            log.warn("开始输注挂接拒绝（挂接非监测中态）：executionNo={}，linkStatus={}", executionNo, link.getLinkStatus());
            throw new BizException(
                    NursingErrorCode.INFUSION_NOT_ACTIVE,
                    HttpStatus.CONFLICT,
                    "输液监测挂接非监测中态（MONITORING 限定）：executionNo=" + executionNo);
        }
        // 消息发送：事务内发布开始输注事件（id 62 五字段契约——deviceId 不进事件：执行域本地
        // 关联信息禁入事件契约，iot 侧按 patientId 维度消费）
        events.publishEvent(new NursingDomainEvent(
                NursingMessagingConstants.EVENT_INFUSION_STARTED,
                new InfusionStartedPayload(
                        executionNo,
                        execution.getPatientId(),
                        execution.getVisitId(),
                        link.getBagLabelCode(),
                        execution.getStartedAt().toInstant())));
        log.info(
                "开始输注挂接完成：executionNo={}，patientId={}，bagLabelCode={}，deviceId={}，operator={}",
                executionNo,
                execution.getPatientId(),
                link.getBagLabelCode(),
                deviceId,
                operator);
    }

    /** 在途输注清单：EXECUTING+INFUSION 执行单 × MONITORING 挂接行聚合（开始时点升序）。 */
    @Override
    @Transactional(readOnly = true)
    public List<ActiveInfusionVO> listActive(String wardId) {
        if (wardId == null || wardId.isBlank()) {
            throw new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "病区编码必填（wardId）");
        }
        // 数据库读操作：在途输液执行单（病区+EXECUTING+INFUSION，开始时点升序）
        List<OrderExecution> executing = executionMapper.selectList(Wrappers.<OrderExecution>lambdaQuery()
                .eq(OrderExecution::getWardId, wardId)
                .eq(OrderExecution::getStatus, ExecutionStatus.EXECUTING.getCode())
                .eq(OrderExecution::getExecutionType, ExecutionType.INFUSION.getCode())
                .orderByAsc(OrderExecution::getStartedAt));
        if (executing.isEmpty()) {
            return List.of();
        }
        // 数据库读操作：挂接行批量装载（宪法 A.4.3-14：单次 IN 查询，循环内纯聚合）
        Map<String, InfusionMonitorLink> linkByExecution = monitorLinkMapper
                .selectList(Wrappers.<InfusionMonitorLink>lambdaQuery()
                        .in(
                                InfusionMonitorLink::getExecutionNo,
                                executing.stream()
                                        .map(OrderExecution::getExecutionNo)
                                        .toList()))
                .stream()
                .collect(Collectors.toMap(InfusionMonitorLink::getExecutionNo, Function.identity(), (a, b) -> a));
        return executing.stream()
                .filter(row -> linkByExecution.get(row.getExecutionNo()) != null
                        && LINK_STATUS_MONITORING.equals(
                                linkByExecution.get(row.getExecutionNo()).getLinkStatus()))
                .map(row -> ActiveInfusionVO.of(row, linkByExecution.get(row.getExecutionNo())))
                .toList();
    }

    /**
     * 告警触发升级挂单（triggered 路）：patientId 直配在途行 → 挂接 MONITORING 过滤 →
     * 逐行升级 CAS（escalation_count+1+latest_alarm_no 双落）+ 挂接任务优先级上调（一次）。
     */
    @Override
    @Transactional
    public int escalateOnAlarmTriggered(long patientId, String alarmNo) {
        // 数据库读操作：患者维度在途输液行（patientId 直配零新表裁决）
        List<OrderExecution> inflight = executionMapper.selectList(Wrappers.<OrderExecution>lambdaQuery()
                .eq(OrderExecution::getPatientId, patientId)
                .eq(OrderExecution::getStatus, ExecutionStatus.EXECUTING.getCode())
                .eq(OrderExecution::getExecutionType, ExecutionType.INFUSION.getCode()));
        List<String> monitored = monitoredExecutionNos(inflight);
        if (monitored.isEmpty()) {
            // 无在途零副作用（brief Step 2 ②：不触任何 CAS/任务面）
            log.info("告警升级挂单零命中（无在途输注监测）：patientId={}，alarmNo={}", patientId, alarmNo);
            return 0;
        }
        return escalateMonitored(monitored, alarmNo, "triggered");
    }

    /** 告警升级动作累计（escalated 路）：latest_alarm_no 反查已挂接在途行，同款升级动作。 */
    @Override
    @Transactional
    public int escalateOnAlarmEscalated(String alarmNo) {
        // 数据库读操作：告警号反查在途输液行（escalated 载荷无 patientId——挂接锚反查）
        List<OrderExecution> inflight = executionMapper.selectList(Wrappers.<OrderExecution>lambdaQuery()
                .eq(OrderExecution::getLatestAlarmNo, alarmNo)
                .eq(OrderExecution::getStatus, ExecutionStatus.EXECUTING.getCode())
                .eq(OrderExecution::getExecutionType, ExecutionType.INFUSION.getCode()));
        if (inflight.isEmpty()) {
            log.info("告警升级累计零命中（告警无挂接在途行）：alarmNo={}", alarmNo);
            return 0;
        }
        return escalateMonitored(
                inflight.stream().map(OrderExecution::getExecutionNo).toList(), alarmNo, "escalated");
    }

    /** 告警关闭复位（closed 路）：挂接锚双复位（escalation_count 保留追溯）。 */
    @Override
    @Transactional
    public int resetAlarmClosed(String alarmNo) {
        String operator = operator();
        // 数据库写操作：执行单行挂接锚复位（latest_alarm_no→NULL；0 行=无挂接/重复关闭幂等）
        int executions = executionMapper.casResetAlarmByAlarmNo(alarmNo, operator);
        // 数据库写操作：挂接行挂接锚复位（同构复位路）
        int links = monitorLinkMapper.casResetAlarmByAlarmNo(alarmNo, operator);
        log.info("告警关闭挂接复位：alarmNo={}，复位执行单行={}，复位挂接行={}（升级计数保留追溯）", alarmNo, executions, links);
        return executions;
    }

    /**
     * 升级动作单点（triggered/escalated 两路共用）：逐行升级 CAS + 挂接锚刷新 + 挂接任务
     * 优先级上调（告警号维度一次）+ 强提醒日志（board WS 推送归 Task 11 接线——类注释实测结论）。
     *
     * @param monitored 监测中执行单号集（已过 MONITORING 过滤或反查锚定），非空
     * @param alarmNo   告警业务号，非空
     * @param route     升级路别（triggered/escalated——日志留痕），非空
     * @return 升级命中行数
     */
    private int escalateMonitored(List<String> monitored, String alarmNo, String route) {
        String operator = operator();
        int escalated = 0;
        for (String executionNo : monitored) {
            // 数据库写操作：升级挂单 CAS（escalation_count+1+latest_alarm_no；0 行=并发终态幂等跳过）
            if (executionMapper.casEscalateAlarm(executionNo, alarmNo, operator) > 0) {
                // 数据库写操作：挂接行挂接锚刷新（triggered 路双落锚；escalated 路幂等同值覆写）
                monitorLinkMapper.casMarkAlarm(executionNo, alarmNo, operator);
                escalated++;
            }
        }
        // 数据库写操作：挂接任务优先级上调（source_ref=告警号；无挂接 0 行跳过——「不新建任务」纪律）
        int tasks = taskMapper.casEscalatePriorityBySourceRef(alarmNo, operator);
        // 强提醒触达（Task 11 前以日志承载——/topic/nursing/board/{wardId} 推送接线点，类注释）
        log.warn(
                "输注告警升级挂单强提醒：route={}，alarmNo={}，patientDimension={}，升级行数={}，任务上调={}",
                route,
                alarmNo,
                monitored,
                escalated,
                tasks);
        return escalated;
    }

    /**
     * 在途行集过滤出监测中执行单号（挂接行批量装载 + MONITORING 判定）。
     *
     * @param inflight 在途输液执行单行集，非空（可为空集）
     * @return 监测中执行单号清单，非空（无在途/无挂接为空清单）
     */
    private List<String> monitoredExecutionNos(List<OrderExecution> inflight) {
        if (inflight.isEmpty()) {
            return List.of();
        }
        // 数据库读操作：挂接行批量装载（宪法 A.4.3-14 单次 IN 查询）
        Map<String, InfusionMonitorLink> linkByExecution = monitorLinkMapper
                .selectList(Wrappers.<InfusionMonitorLink>lambdaQuery()
                        .in(
                                InfusionMonitorLink::getExecutionNo,
                                inflight.stream()
                                        .map(OrderExecution::getExecutionNo)
                                        .toList()))
                .stream()
                .collect(Collectors.toMap(InfusionMonitorLink::getExecutionNo, Function.identity(), (a, b) -> a));
        return inflight.stream()
                .map(OrderExecution::getExecutionNo)
                .filter(no -> linkByExecution.get(no) != null
                        && LINK_STATUS_MONITORING.equals(linkByExecution.get(no).getLinkStatus()))
                .toList();
    }

    /** 操作者取值（MQ 链路 SYSTEM 桥接置位；无登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
