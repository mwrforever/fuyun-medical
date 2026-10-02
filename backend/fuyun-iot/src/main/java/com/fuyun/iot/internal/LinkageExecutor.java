package com.fuyun.iot.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
import com.fuyun.iot.api.payload.CallTriggeredPayload;
import com.fuyun.iot.api.payload.LinkageExecutedPayload;
import com.fuyun.iot.cache.IotSeqGate;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotAlarmEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.entity.IotLinkageLogEntity;
import com.fuyun.iot.entity.IotLinkageRuleEntity;
import com.fuyun.iot.enums.LinkageActionResult;
import com.fuyun.iot.enums.LinkageTriggerSource;
import com.fuyun.iot.mapper.IotAlarmMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.mapper.IotLinkageLogMapper;
import com.fuyun.iot.mapper.IotLinkageRuleMapper;
import com.fuyun.iot.service.ITelemetryPushService;
import com.fuyun.nursing.api.NursingTaskLinkagePort;
import com.fuyun.nursing.api.NursingTaskLinkageRequest;
import com.fuyun.nursing.api.NursingTaskLinkageResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 联动执行器（FU-M14-10 触发→动作编排唯一执行点，P2 PR-2 Task 9）：ALARM_TRIGGERED 源消费告警
 * 事件 → 条件匹配启用规则集 → 逐规则执行动作（失败同步快速重试）→ iot_linkage_log 落行 → 发布
 * iot.linkage.executed。联动与告警规则解耦（纯事件驱动旁路，告警侧零感知）；同一告警可挂多条
 * 联动规则（逐条独立留痕，单条失败不阻断其余）。
 *
 * <p><b>条件匹配语义</b>：trigger_condition 为 JSONB 键值等值匹配（键词表 alarm_type/metric_code/
 * device_type，服务层拒保存未知键）——键缺席=通配；alarm_type 与 metric_code 在 ALARM_TRIGGERED
 * 源同源（载荷 metricCode 兼承载告警类型与指标编码两义，两键并留以对齐词表并服务后续源）；
 * device_type 以设备档案类型解析（事件级单查，档案缺位视为不命中）；空条件对象=全部命中。
 *
 * <p><b>五类动作语义（P2 面）</b>：NOTIFY=WS 告警主题重复强化（alarm 帧重推一次，linkageNo 经
 * STOMP 头携带作联动标记）+留痕；M01_NOTIFY=M01 通知中心缺位降级为留痕+warn（GC17①）；
 * CALL_TRANSFER=发布 iot.call.triggered 扇出至 ward 呼叫域落 ward_call 行（Task 12 审查
 * Important-1 回接闭合——动作=事件已发布，回执 SUCCESS；Task 9 联调债正式闭合）；
 * NURSING_TASK=M05 护理任务创建（PR-3 Task 12 回接闭合：经 nursing api 端口进程内直调幂等
 * 创建 IOT_LINKAGE 任务，成功/幂等重放均回执 SUCCESS，port 抛 BizException 走重试——回接前
 * 的 NursingUnavailable 暂存注记随本回接消除）；WARD_BROADCAST=M16 病区播报（域缺位暂存
 * PENDING，唯一暂存承接项）。
 *
 * <p><b>重试形态（实测申报）</b>：P2 全部动作皆本地快操作（NOTIFY=内存 SimpleBroker 进程内
 * 分发；NURSING_TASK=进程内端口直调；其余为本地留痕/暂存），无慢外呼依赖——按 brief 允许
 * 形态采用<b>同步三次内快速重试</b>（首试+至多 3 次重试，重试间零等待）；brief 的 1s/2s/4s
 * 指数退避为慢外呼恢复等待设计，对进程内快操作无恢复意义且禁 Thread.sleep 阻塞消费线程
 * （Task 9 控制面裁决），故不引入退避等待；CALL_TRANSFER/NURSING_TASK 已分别随 PR-2 Task 12
 * 与 PR-3 Task 12 回接（快操作形态，同步快速重试适用），WARD_BROADCAST 回接时一并引入异步
 * 重试形态。耗尽落 FAILED 终态，
 * 人工重推走 POST /linkage-logs/{no}/retry（服务层 CAS 承载）。
 *
 * <p><b>事务边界</b>：动作执行在事务外（推送禁入事务，宪法 A.4.2-7）；「日志落行+事件发布」经
 * TransactionTemplate 同事务承载（AFTER_COMMIT 出 MQ——回滚事务不发布；不挂 @Transactional 方法级
 * 事务以规避编排器自调用代理失效，CommandDispatcher 同款裁决）。调用方约束：本类入口须在无激活
 * 事务的线程调起（MQ 消费线程语义）。发布 PENDING 结果事件属有意行为：审计订阅方（M05/M16）据
 * 此留痕暂存面，回接方收口后经 updated_at 观测。
 *
 * <p>归 internal/ 包：容器驱动链路的模块内编排设施，禁止外部引用（宪法 B.1）；模块内服务层
 * 仅经 {@link #execute} 复用动作执行（人工重推面），装配归 fuyun-app IotConfig @Import。
 */
@Slf4j
public class LinkageExecutor {

    /** 动作失败自动重试次数上限（brief 冻结「三次」；retry_count 列承载已耗次数） */
    static final int MAX_RETRIES = 3;

    /**
     * CALL_TRANSFER 扇出呼叫的固定类型：EMERGENCY（告警驱动的转接呼叫固定紧急档——ward 域
     * CallType 词表 V1100 冻结值，iot 侧以字面量承载防跨模块 enum 耦合，B.2-2）。
     */
    private static final String CALL_TRANSFER_CALL_TYPE = "EMERGENCY";

    /**
     * NURSING_TASK 动作配置缺席时的任务类型回退值：IOT_LINKAGE（nursing 域 TaskType 词表值，
     * 专为设备联动任务预留——iot 侧以字面量承载防跨模块 enum 耦合，B.2-2 同 CALL_TRANSFER 先例）。
     */
    private static final String DEFAULT_NURSING_TASK_TYPE = "IOT_LINKAGE";

    /** 错误消息截断上限：error_msg 列宽 VARCHAR(500)（列宽防线，防摘要超长致落库失败） */
    private static final int ERROR_MSG_MAX_LENGTH = 500;

    private final IotLinkageRuleMapper ruleMapper;

    private final IotLinkageLogMapper logMapper;

    private final IotDeviceMapper deviceMapper;

    private final IotAlarmMapper alarmMapper;

    private final NursingTaskLinkagePort nursingTaskPort;

    private final IotSeqGate seqGate;

    private final ITelemetryPushService pushService;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param ruleMapper      联动规则 mapper，非空；启用规则装载通道
     * @param logMapper       联动日志 mapper，非空；执行留痕落行通道
     * @param deviceMapper    设备档案 mapper，非空；device_type 条件解析通道
     * @param alarmMapper     告警行 mapper，非空；NOTIFY/CALL_TRANSFER/NURSING_TASK 动作按告警号定位通道
     * @param nursingTaskPort 护理任务联动端口（nursing api），非空；NURSING_TASK 动作进程内直调
     *                        出口（实现 Bean 由 fuyun-app NursingWebConfig 装配，PR-3 Task 12 回接）
     * @param seqGate         业务号发号器，非空；联动号 LG 取号出口
     * @param pushService     STOMP 推送服务，非空；NOTIFY 动作 WS 推送出口
     * @param events          Spring 事件发布器，非空；executed 事件事务内发布入口（AFTER_COMMIT 出 MQ）
     * @param transactions    事务模板，非空；日志落行+事件发布同事务承载
     * @param objectMapper    JSON 转换器，非空；触发条件/动作配置 JSONB 解析
     */
    public LinkageExecutor(
            IotLinkageRuleMapper ruleMapper,
            IotLinkageLogMapper logMapper,
            IotDeviceMapper deviceMapper,
            IotAlarmMapper alarmMapper,
            NursingTaskLinkagePort nursingTaskPort,
            IotSeqGate seqGate,
            ITelemetryPushService pushService,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.logMapper = logMapper;
        this.deviceMapper = deviceMapper;
        this.alarmMapper = alarmMapper;
        this.nursingTaskPort = nursingTaskPort;
        this.seqGate = seqGate;
        this.pushService = pushService;
        this.events = events;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    /**
     * 单次动作执行结果载体（动作语义的终态回执：结果 + 已耗重试次数 + 失败原因 + 判定时刻）。
     *
     * @param result     动作执行结果（SUCCESS/FAILED/PENDING），非空
     * @param retryCount 本次执行链内已耗自动重试次数（0=首试即成；人工重推累计由 CAS 承接）
     * @param errorMsg   失败原因/暂存注记（SUCCESS 为空；超列宽截断），可空
     * @param executedAt 执行判定时刻（UTC），非空
     */
    public record ActionExecution(LinkageActionResult result, int retryCount, String errorMsg, Instant executedAt) {}

    /**
     * 告警触发事件编排入口（ALARM_TRIGGERED 源，IotAlarmEventListener 委托）：启用规则装载 →
     * 条件匹配 → 逐规则「动作执行 → 落行 → 发布」。
     *
     * @param payload 告警触发事件载荷，非空；来源：iot.alarm.triggered 自事件消费解析产物
     */
    public void onAlarmTriggered(AlarmTriggeredPayload payload) {
        // 设备档案单查（事件级一次）：device_type 条件解析面；档案缺位视为该键不命中
        IotDeviceEntity device = payload.deviceId() == null ? null : deviceMapper.selectById(payload.deviceId());
        String deviceType = device == null ? null : device.getDeviceType();
        List<IotLinkageRuleEntity> rules = loadEnabledRules(LinkageTriggerSource.ALARM_TRIGGERED);
        int matched = 0;
        for (IotLinkageRuleEntity rule : rules) {
            if (!matches(rule.getTriggerCondition(), payload.metricCode(), payload.metricCode(), deviceType)) {
                continue;
            }
            matched++;
            executeAndRecord(rule, LinkageTriggerSource.ALARM_TRIGGERED, payload.alarmNo(), Instant.now());
        }
        if (matched == 0) {
            // 未命中任何联动规则：info 留痕（告警侧零感知的旁路语义，非异常路径）
            log.info(
                    "告警事件未命中联动规则，跳过：alarmNo={}，metricCode={}，candidates={}",
                    payload.alarmNo(),
                    payload.metricCode(),
                    rules.size());
        }
    }

    /**
     * 单规则动作执行（失败同步快速重试内嵌）：供编排链与人工重推（服务层）共用。
     *
     * <p>重试语义：首试失败后至多重试 {@link #MAX_RETRIES} 次（零等待快速重试——动作皆本地快
     * 操作，实测申报见类注释），耗尽返回 FAILED；重试仅对可失败动作有意义（NOTIFY），暂存类
     * 动作确定性返回 PENDING 不进重试路径。
     *
     * @param rule       命中联动规则，非空
     * @param triggerRef 触发来源引用（告警号等），非空
     * @param linkageNo  联动执行业务号，非空（人工重推沿既有号——WS 联动标记指向同一执行行）
     * @return 动作执行结果，非空
     */
    public ActionExecution execute(IotLinkageRuleEntity rule, String triggerRef, String linkageNo) {
        int attempt = 0;
        while (true) {
            try {
                return dispatch(rule, triggerRef, linkageNo, attempt);
            } catch (RuntimeException e) {
                attempt++;
                if (attempt > MAX_RETRIES) {
                    // 重试耗尽：FAILED 终态（error_msg 截断留痕），交服务层人工重推面
                    log.error(
                            "联动动作重试耗尽，落 FAILED：linkageNo={}，ruleId={}，actionType={}，attempts={}",
                            linkageNo,
                            rule.getId(),
                            rule.getActionType(),
                            attempt,
                            e);
                    return new ActionExecution(
                            LinkageActionResult.FAILED, MAX_RETRIES, truncate(e.getMessage()), Instant.now());
                }
                log.warn(
                        "联动动作执行失败，同步快速重试（{}/{}）：linkageNo={}，ruleId={}，原因={}",
                        attempt,
                        MAX_RETRIES,
                        linkageNo,
                        rule.getId(),
                        e.getMessage());
            }
        }
    }

    /**
     * 单规则「动作执行 → 日志落行 → 事件发布」编排（取号在动作前——NOTIFY 联动标记需号）。
     *
     * @param rule         命中联动规则，非空
     * @param source       触发来源，非空
     * @param triggerRef   触发来源引用，非空
     * @param executedBase 执行判定基准时刻（载荷/日志 executedAt 同源），非空
     */
    private void executeAndRecord(
            IotLinkageRuleEntity rule, LinkageTriggerSource source, String triggerRef, Instant executedBase) {
        String linkageNo = seqGate.nextLinkageNo();
        // 动作执行（事务外——推送禁入事务，宪法 A.4.2-7；失败重试内嵌见 execute）
        ActionExecution outcome = execute(rule, triggerRef, linkageNo);
        // 日志落行 + 事件发布同事务（AFTER_COMMIT 出 MQ；回滚事务不发布）
        transactions.executeWithoutResult(status -> {
            // 数据库写操作：联动执行留痕落行（雪花 id 由 MP ASSIGN_ID 生成）
            logMapper.insert(buildLogEntity(rule, source, triggerRef, linkageNo, outcome, executedBase));
            // 消息发送：事务内发布执行结果事件（PENDING 亦发布——审计订阅方留痕暂存面）
            events.publishEvent(new IotDomainEvent(
                    IotMessagingConstants.EVENT_LINKAGE_EXECUTED,
                    new LinkageExecutedPayload(
                            linkageNo,
                            rule.getId(),
                            source.getCode(),
                            triggerRef,
                            rule.getActionType().getCode(),
                            outcome.result().getCode(),
                            outcome.executedAt()),
                    executedBase,
                    currentTraceId()));
        });
        log.info(
                "联动已执行并留痕：linkageNo={}，ruleId={}，actionType={}，result={}，retryCount={}，triggerRef={}",
                linkageNo,
                rule.getId(),
                rule.getActionType(),
                outcome.result(),
                outcome.retryCount(),
                triggerRef);
    }

    /**
     * 五类动作分派（brief 冻结语义的单一裁决点）：NOTIFY 推送 / M01_NOTIFY 降级 / CALL_TRANSFER
     * 与 NURSING_TASK 已回接（事件扇出/端口直调）/ WARD_BROADCAST 目标域缺位暂存。
     *
     * @param rule       命中联动规则，非空
     * @param triggerRef 触发来源引用（告警号等），非空
     * @param linkageNo  联动执行业务号，非空
     * @param attempt    当前为第几次执行（0=首试），非负
     * @return 动作执行结果，非空
     */
    private ActionExecution dispatch(IotLinkageRuleEntity rule, String triggerRef, String linkageNo, int attempt) {
        return switch (rule.getActionType()) {
            // WS 告警主题重复强化：按 trigger_ref 定位告警行重推一次（带 linkageNo 标记头）
            case NOTIFY -> {
                notifyAlarm(triggerRef, linkageNo);
                yield succeeded(attempt);
            }
            // M01 通知中心缺位（GC17①）：降级为留痕成功 + warn 告警（M01 接入后回接真实通知）
            case M01_NOTIFY -> {
                log.warn(
                        "联动动作降级（M01 通知中心缺位，GC17① 留痕面）：linkageNo={}，ruleId={}，triggerRef={}",
                        linkageNo,
                        rule.getId(),
                        triggerRef);
                yield succeeded(attempt);
            }
            // 已回接双动作：CALL_TRANSFER 发布 iot.call.triggered 扇出至 ward 呼叫域（PR-2 Task 12）；
            // NURSING_TASK 经 nursing api 端口幂等创建护理任务（PR-3 Task 12 回接）
            case CALL_TRANSFER -> transferCall(rule, triggerRef, linkageNo, attempt);
            case NURSING_TASK -> createNursingTask(rule, triggerRef, linkageNo, attempt);
            // M16 病区播报域缺位：唯一暂存承接项（回接点见 deferToTargetDomain）
            case WARD_BROADCAST -> deferToTargetDomain(rule, linkageNo, attempt);
        };
    }

    /**
     * NOTIFY 动作执行：按 trigger_ref（告警号）定位告警行后经推送服务重复强化推送。
     *
     * @param triggerRef 触发来源引用（告警号），非空
     * @param linkageNo  联动执行业务号（STOMP 头联动标记），非空
     * @throws IllegalStateException 告警行定位失败（重试耗尽后落 FAILED 留痕）
     */
    private void notifyAlarm(String triggerRef, String linkageNo) {
        // 数据库读操作：自然键 alarm_no 单查（@TableLogic 自动携带 deleted=0）
        IotAlarmEntity alarm = alarmMapper.selectOne(
                Wrappers.<IotAlarmEntity>lambdaQuery().eq(IotAlarmEntity::getAlarmNo, triggerRef));
        if (alarm == null) {
            // 内部断言：告警行由本模块告警引擎先落库后发事件（同源），无命中属数据不一致防御，
            // 非用户输入路径；保留 ISE 走重试→FAILED 留痕收口
            throw new IllegalStateException("联动 NOTIFY 动作定位告警行失败（trigger_ref 无命中）：" + triggerRef);
        }
        pushService.pushLinkageNotify(alarm, linkageNo);
    }

    /**
     * 【回接点】目标业务域未上线动作的暂存承接（P2 面，联调债登记）：<b>唯一承接项
     * WARD_BROADCAST → M16 病区播报域回接</b>——播报通道落地后同款改写（暂存注记
     * WardBroadcastUnavailable）。CALL_TRANSFER 已随 PR-2 Task 12 审查 Important-1 回接
     * （{@link #transferCall}），NURSING_TASK 已随 PR-3 Task 12 回接
     * （{@link #createNursingTask}），暂存注记 WardUnavailable/NursingUnavailable 均消除。
     *
     * <p>回接前本方法确定性返回 PENDING + 域缺位注记（error_msg 承载，日志行为暂存行）；
     * PENDING 行不入自动重试面（非失败语义），人工重推亦被服务层以「仅 FAILED 可重推」拒绝，
     * 收口唯一路径为回接方落域行。
     *
     * @param rule      命中联动规则（WARD_BROADCAST 型），非空
     * @param linkageNo 联动执行业务号，非空
     * @param attempt   当前为第几次执行（0=首试），非负
     * @return PENDING 暂存结果（注记承载域缺位原因），非空
     */
    private ActionExecution deferToTargetDomain(IotLinkageRuleEntity rule, String linkageNo, int attempt) {
        String note = "WardBroadcastUnavailable：M16 病区播报域未上线（回接方收口）";
        log.info(
                "联动动作暂存（目标域未上线）：linkageNo={}，ruleId={}，actionType={}，note={}",
                linkageNo,
                rule.getId(),
                rule.getActionType(),
                note);
        return new ActionExecution(LinkageActionResult.PENDING, attempt, note, Instant.now());
    }

    /**
     * NURSING_TASK 动作执行（PR-3 Task 12 回接，NursingUnavailable 暂存注记消除）：按触发引用
     * （告警号）定位告警行取绑定快照（patientId/visitId/wardId），组装端口请求经
     * {@link NursingTaskLinkagePort} 进程内直调幂等创建 IOT_LINKAGE 护理任务（source_ref 落
     * linkageNo，同号重放由 nursing 侧回查原任务）。
     *
     * <p>三路回执语义（brief 冻结）：创建成功与幂等重放（created=false）均回执 SUCCESS
     * （error_msg 空——回接前的 NursingUnavailable 暂存注记自此不再产生）；port 抛
     * BizException 直接上抛进 {@link #execute} 同步快速重试路径，耗尽落 FAILED 终态（可人工
     * 重推——幂等键保证重推不重复建任务）。
     *
     * <p><b>载荷组件语义申报</b>：wardId=iot 域病区 id 数字串（rule.targetWardId 优先，空=告警
     * 行触发源病区——与护理域病区编码分属两标识空间，Task 11 申报同款，nursing 侧无 id→code
     * 映射 api）；taskType/title 取规则 actionConfig（action_config JSONB 透传），taskType
     * 缺席回退 IOT_LINKAGE 字面量（nursing 词表不跨模块引用，B.2-2）；planTime=执行时刻
     * （即联即办确认类任务，服务器时间 GC25）；title 为契约承载字段（nursing_task 无标题列）。
     *
     * @param rule       命中联动规则，非空
     * @param triggerRef 触发来源引用（告警号），非空
     * @param linkageNo  联动执行业务号（幂等键），非空
     * @param attempt    当前为第几次执行（0=首试），非负
     * @return SUCCESS（成功/幂等重放统一），非空
     * @throws IllegalStateException 告警行定位失败（trigger_ref 无命中——重试耗尽后落 FAILED 留痕）
     */
    private ActionExecution createNursingTask(
            IotLinkageRuleEntity rule, String triggerRef, String linkageNo, int attempt) {
        // 数据库读操作：自然键 alarm_no 单查（@TableLogic 自动携带 deleted=0）——绑定快照字段来源
        IotAlarmEntity alarm = alarmMapper.selectOne(
                Wrappers.<IotAlarmEntity>lambdaQuery().eq(IotAlarmEntity::getAlarmNo, triggerRef));
        if (alarm == null) {
            // 内部断言：告警行由本模块告警引擎先落库后发事件（同源），无命中属数据不一致防御，
            // 非用户输入路径；保留 ISE 走重试→FAILED 留痕收口（NOTIFY/CALL_TRANSFER 同款）
            throw new IllegalStateException("联动 NURSING_TASK 动作定位告警行失败（trigger_ref 无命中）：" + triggerRef);
        }
        // 目标病区优先规则指定，空=跟随触发源病区（iot 域 id 数字串，语义申报见方法注）
        Long wardSource = rule.getTargetWardId() != null ? rule.getTargetWardId() : alarm.getWardId();
        NursingTaskLinkageResult outcome = nursingTaskPort.createTask(new NursingTaskLinkageRequest(
                linkageNo,
                wardSource == null ? null : String.valueOf(wardSource),
                alarm.getPatientId(),
                alarm.getVisitId(),
                actionConfigText(rule, "taskType", DEFAULT_NURSING_TASK_TYPE),
                actionConfigText(rule, "title", null),
                OffsetDateTime.now()));
        if (outcome.created()) {
            log.info(
                    "联动 NURSING_TASK 已创建护理任务：linkageNo={}，taskNo={}，ruleId={}，alarmNo={}，visitId={}，wardId={}",
                    linkageNo,
                    outcome.taskNo(),
                    rule.getId(),
                    triggerRef,
                    alarm.getVisitId(),
                    wardSource);
        } else {
            // 幂等重放视为成功：PENDING 行人工重推/链路重投由 nursing 侧同号回查原任务承载
            log.info(
                    "联动 NURSING_TASK 幂等重放（回查原任务）：linkageNo={}，taskNo={}，ruleId={}，alarmNo={}",
                    linkageNo,
                    outcome.taskNo(),
                    rule.getId(),
                    triggerRef);
        }
        return succeeded(attempt);
    }

    /**
     * 规则动作配置取值（action_config JSONB 键值读取，NURSING_TASK 动作参数通道）：配置缺席/
     * 原文损坏按缺省值承载并 warn 留痕（损坏不阻断动作——任务类型回退默认值仍可创建）。
     *
     * @param rule         命中联动规则，非空
     * @param key          动作配置键名（taskType/title），非空
     * @param defaultValue 配置缺席时的缺省值（null=允许缺席无缺省），可空
     * @return 配置文本值或缺省值，可空
     */
    private String actionConfigText(IotLinkageRuleEntity rule, String key, String defaultValue) {
        String configJson = rule.getActionConfig();
        if (configJson == null || configJson.isBlank()) {
            return defaultValue;
        }
        try {
            JsonNode value = objectMapper.readTree(configJson).path(key);
            return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? defaultValue : value.asText();
        } catch (Exception e) {
            log.warn(
                    "联动动作配置原文损坏，按缺省值承载：ruleId={}，key={}，config={}，原因={}",
                    rule.getId(),
                    key,
                    configJson,
                    e.getMessage());
            return defaultValue;
        }
    }

    /**
     * CALL_TRANSFER 动作执行（Task 12 审查 Important-1 回接，Task 9 联调债闭合）：按触发引用
     * （告警号）定位告警行取绑定快照（deviceId/wardId），小事务内发布 iot.call.triggered
     * （V1004 id 81，IotDomainPublisher AFTER_COMMIT 出 MQ——事务内禁直发红线），由 ward 呼叫域
     * 消费落 ward_call 行（q.ward.iot.call.triggered 队列，幂等域 ward）。
     *
     * <p>动作语义=事件已发布（event 扇出给 ward），发布成功即回执 SUCCESS；发布事务失败异常上抛
     * 进 execute 同步重试路径（发布本身无回执——ward 消费侧幂等域承接 at-least-once 语义）。
     *
     * <p><b>载荷组件语义申报（GC4 冻结组件名不变）</b>：callNo=触发引用/预留——联动触发无呼叫域
     * 业务号（ward 呼叫号由 ward 侧 WardSeqGate 签发），本路径填联动执行号 linkageNo 作触发链
     * 唯一引用，ward 侧 source_ref 落行留痕对账；callType=EMERGENCY（告警驱动的转接呼叫固定
     * 紧急档，ward 域 CallType 词表 V1100 冻结值）；bedId=null（iot_alarm 无床位锚，ward 侧
     * 可空偏差已裁定成立）。
     *
     * @param rule      命中联动规则，非空
     * @param triggerRef 触发来源引用（告警号），非空
     * @param linkageNo 联动执行业务号，非空
     * @param attempt   当前为第几次执行（0=首试），非负
     * @return SUCCESS（事件已发布；发布失败异常上抛走重试）
     * @throws IllegalStateException 告警行定位失败（trigger_ref 无命中——重试耗尽后落 FAILED 留痕）
     */
    private ActionExecution transferCall(IotLinkageRuleEntity rule, String triggerRef, String linkageNo, int attempt) {
        // 数据库读操作：自然键 alarm_no 单查（@TableLogic 自动携带 deleted=0）——绑定快照字段来源
        IotAlarmEntity alarm = alarmMapper.selectOne(
                Wrappers.<IotAlarmEntity>lambdaQuery().eq(IotAlarmEntity::getAlarmNo, triggerRef));
        if (alarm == null) {
            // 内部断言：告警行由本模块告警引擎先落库后发事件（同源），无命中属数据不一致防御，
            // 非用户输入路径；保留 ISE 走重试→FAILED 留痕收口
            throw new IllegalStateException("联动 CALL_TRANSFER 动作定位告警行失败（trigger_ref 无命中）：" + triggerRef);
        }
        Instant triggeredAt = Instant.now();
        // 消息发送：小事务内发布呼叫触发事件（AFTER_COMMIT 出 MQ；发布失败事务回滚且不发布）
        transactions.executeWithoutResult(status -> events.publishEvent(new IotDomainEvent(
                IotMessagingConstants.EVENT_CALL_TRIGGERED,
                new CallTriggeredPayload(
                        linkageNo, alarm.getDeviceId(), CALL_TRANSFER_CALL_TYPE, null, alarm.getWardId(), triggeredAt),
                triggeredAt,
                currentTraceId())));
        log.info(
                "联动 CALL_TRANSFER 已回接（事件扇出 ward 呼叫域）：linkageNo={}，ruleId={}，alarmNo={}，wardId={}",
                linkageNo,
                rule.getId(),
                triggerRef,
                alarm.getWardId());
        return new ActionExecution(LinkageActionResult.SUCCESS, attempt, null, triggeredAt);
    }

    /** 首试/重试成功的执行结果（retryCount 承载已耗重试次数）。 */
    private static ActionExecution succeeded(int attempt) {
        return new ActionExecution(LinkageActionResult.SUCCESS, attempt, null, Instant.now());
    }

    /**
     * 组装联动日志实体（命中时规则词表快照 + 结果初值 + 审计留痕交数据库默认值）。
     *
     * @param rule         命中联动规则，非空
     * @param source       触发来源，非空
     * @param triggerRef   触发来源引用，非空
     * @param linkageNo    联动执行业务号，非空
     * @param outcome      动作执行结果，非空
     * @param executedBase 执行判定基准时刻，非空
     * @return 日志实体，非空
     */
    private static IotLinkageLogEntity buildLogEntity(
            IotLinkageRuleEntity rule,
            LinkageTriggerSource source,
            String triggerRef,
            String linkageNo,
            ActionExecution outcome,
            Instant executedBase) {
        IotLinkageLogEntity entity = new IotLinkageLogEntity();
        entity.setLinkageNo(linkageNo);
        entity.setRuleId(rule.getId());
        entity.setTriggerSource(source);
        entity.setTriggerRef(triggerRef);
        entity.setActionType(rule.getActionType());
        entity.setActionResult(outcome.result());
        entity.setRetryCount(outcome.retryCount());
        entity.setErrorMsg(outcome.errorMsg());
        entity.setExecutedAt(OffsetDateTime.ofInstant(executedBase, ZoneOffset.UTC));
        return entity;
    }

    /**
     * 触发条件匹配判定（JSONB 键值等值匹配）：键缺席=通配；alarm_type/metric_code 与载荷指标
     * 编码（告警类型同源）比对；device_type 与设备档案类型比对（档案缺位不命中）；未知键不命中
     * （服务层拒保存未知键，此为脏数据兜底防御）；条件原文缺失/损坏按「不命中」保守处置并 warn。
     *
     * @param conditionJson 触发条件 JSONB 原文，可空（空对象=全部命中）
     * @param alarmType     告警类型（载荷 metricCode），可空
     * @param metricCode    指标编码（载荷 metricCode），可空
     * @param deviceType    设备档案类型（档案缺位为 null），可空
     * @return true=该规则参与执行
     */
    private boolean matches(String conditionJson, String alarmType, String metricCode, String deviceType) {
        if (conditionJson == null || conditionJson.isBlank()) {
            // 条件缺席按空对象语义全命中（列 NOT NULL，此处为脏数据防御面）
            return true;
        }
        JsonNode condition;
        try {
            condition = objectMapper.readTree(conditionJson);
        } catch (Exception e) {
            log.warn("联动触发条件原文损坏，按不命中保守处置：condition={}，原因={}", conditionJson, e.getMessage());
            return false;
        }
        if (!condition.isObject()) {
            log.warn("联动触发条件非 JSON 对象，按不命中保守处置：condition={}", conditionJson);
            return false;
        }
        // 逐键等值匹配：键在条件中缺席即通配（不参与裁决）
        for (Map.Entry<String, JsonNode> entry : condition.properties()) {
            String expected = entry.getValue().asText("");
            boolean hit =
                    switch (entry.getKey()) {
                        case "alarm_type" -> expected.equals(alarmType);
                        case "metric_code" -> expected.equals(metricCode);
                        case "device_type" -> deviceType != null && expected.equals(deviceType);
                        default -> false;
                    };
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按触发来源装载启用规则（@TableLogic 自动携带 deleted=0，enabled=true 过滤禁用规则）。
     *
     * @param source 触发来源，非空
     * @return 启用规则清单，非空
     */
    private List<IotLinkageRuleEntity> loadEnabledRules(LinkageTriggerSource source) {
        // 数据库读操作：启用规则单查（idx_linkage_rule_source_enabled 准入）
        return ruleMapper.selectList(Wrappers.<IotLinkageRuleEntity>lambdaQuery()
                .eq(IotLinkageRuleEntity::getTriggerSource, source)
                .eq(IotLinkageRuleEntity::getEnabled, true));
    }

    /**
     * 错误消息列宽防线：超 VARCHAR(500) 截断（失败摘要留痕保留前段）。
     *
     * @param message 原始错误消息，可空
     * @return 列宽内文本，可空
     */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= ERROR_MSG_MAX_LENGTH ? message : message.substring(0, ERROR_MSG_MAX_LENGTH);
    }

    /**
     * 发布点捕获 traceId（AFTER_COMMIT 回调执行时 MDC 不可依赖——构造事件时捕获透传）。
     *
     * @return 当前线程 traceId；无日志上下文为 null（信封契约允许）
     */
    private static String currentTraceId() {
        return MDC.get(IotMessagingConstants.TRACE_ID_MDC_KEY);
    }
}
