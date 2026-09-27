package com.fuyun.iot.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
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
 * CALL_TRANSFER=ward 呼叫域回接点（Task 12 落地前暂存 PENDING，见
 * {@link #deferToTargetDomain}）；NURSING_TASK=M05 护理任务创建（PR-3 回接，暂存 PENDING）；
 * WARD_BROADCAST=M16 病区播报（域缺位暂存 PENDING）。
 *
 * <p><b>重试形态（实测申报）</b>：P2 全部动作皆本地快操作（NOTIFY=内存 SimpleBroker 进程内
 * 分发；其余为本地留痕/暂存），无慢外呼依赖——按 brief 允许形态采用<b>同步三次内快速重试</b>
 * （首试+至多 3 次重试，重试间零等待）；brief 的 1s/2s/4s 指数退避为慢外呼恢复等待设计，对
 * 进程内 broker 无恢复意义且禁 Thread.sleep 阻塞消费线程（Task 9 控制面裁决），故不引入退避
 * 等待；动作形态升级为真实外呼（回接 Task 12/PR-3）时一并引入异步重试形态。耗尽落 FAILED 终态，
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

    /** 错误消息截断上限：error_msg 列宽 VARCHAR(500)（列宽防线，防摘要超长致落库失败） */
    private static final int ERROR_MSG_MAX_LENGTH = 500;

    private final IotLinkageRuleMapper ruleMapper;

    private final IotLinkageLogMapper logMapper;

    private final IotDeviceMapper deviceMapper;

    private final IotAlarmMapper alarmMapper;

    private final IotSeqGate seqGate;

    private final ITelemetryPushService pushService;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param ruleMapper   联动规则 mapper，非空；启用规则装载通道
     * @param logMapper    联动日志 mapper，非空；执行留痕落行通道
     * @param deviceMapper 设备档案 mapper，非空；device_type 条件解析通道
     * @param alarmMapper  告警行 mapper，非空；NOTIFY 动作按告警号定位通道
     * @param seqGate      业务号发号器，非空；联动号 LG 取号出口
     * @param pushService  STOMP 推送服务，非空；NOTIFY 动作 WS 推送出口
     * @param events       Spring 事件发布器，非空；executed 事件事务内发布入口（AFTER_COMMIT 出 MQ）
     * @param transactions 事务模板，非空；日志落行+事件发布同事务承载
     * @param objectMapper JSON 转换器，非空；触发条件 JSONB 解析
     */
    public LinkageExecutor(
            IotLinkageRuleMapper ruleMapper,
            IotLinkageLogMapper logMapper,
            IotDeviceMapper deviceMapper,
            IotAlarmMapper alarmMapper,
            IotSeqGate seqGate,
            ITelemetryPushService pushService,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.logMapper = logMapper;
        this.deviceMapper = deviceMapper;
        this.alarmMapper = alarmMapper;
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
     * 五类动作分派（brief 冻结语义的单一裁决点）：NOTIFY 推送 / M01_NOTIFY 降级 / 其余三类
     * 目标域缺位暂存。
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
            // ward 呼叫/病区播报与 M05 护理任务域缺位：暂存 PENDING（回接点见 deferToTargetDomain）
            case CALL_TRANSFER, WARD_BROADCAST, NURSING_TASK -> deferToTargetDomain(rule, linkageNo, attempt);
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
            throw new IllegalStateException("联动 NOTIFY 动作定位告警行失败（trigger_ref 无命中）：" + triggerRef);
        }
        pushService.pushLinkageNotify(alarm, linkageNo);
    }

    /**
     * 【回接点】目标业务域未上线动作的暂存承接（P2 面，联调债登记）：
     *
     * <ul>
     *   <li><b>CALL_TRANSFER → Task 12 回接</b>：ward 呼叫域落 ward_call 创建端口后，本分支改为
     *       调用端口落呼叫行并按回执置 SUCCESS/FAILED（暂存注记 WardUnavailable 消除）；</li>
     *   <li><b>WARD_BROADCAST → M16 病区播报域回接</b>：播报通道落地后同款改写（暂存注记
     *       WardBroadcastUnavailable）；</li>
     *   <li><b>NURSING_TASK → PR-3 回接</b>：M05 护理任务创建端口落地后同款改写（暂存注记
     *       NursingUnavailable）。</li>
     * </ul>
     *
     * <p>回接前本方法确定性返回 PENDING + 域缺位注记（error_msg 承载，日志行为暂存行）；
     * PENDING 行不入自动重试面（非失败语义），人工重推亦被服务层以「仅 FAILED 可重推」拒绝，
     * 收口唯一路径为回接方落域行。
     *
     * @param rule      命中联动规则，非空
     * @param linkageNo 联动执行业务号，非空
     * @param attempt   当前为第几次执行（0=首试），非负
     * @return PENDING 暂存结果（注记承载域缺位原因），非空
     */
    private ActionExecution deferToTargetDomain(IotLinkageRuleEntity rule, String linkageNo, int attempt) {
        String note =
                switch (rule.getActionType()) {
                    case CALL_TRANSFER -> "WardUnavailable：ward 呼叫域未上线（Task 12 回接 ward_call 落行）";
                    case WARD_BROADCAST -> "WardBroadcastUnavailable：M16 病区播报域未上线（回接方收口）";
                    case NURSING_TASK -> "NursingUnavailable：M05 护理任务创建未上线（PR-3 回接）";
                    default -> throw new IllegalStateException("暂存分派不承接的动作类型：" + rule.getActionType());
                };
        log.info(
                "联动动作暂存（目标域未上线）：linkageNo={}，ruleId={}，actionType={}，note={}",
                linkageNo,
                rule.getId(),
                rule.getActionType(),
                note);
        return new ActionExecution(LinkageActionResult.PENDING, attempt, note, Instant.now());
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
