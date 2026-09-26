package com.fuyun.iot.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.api.payload.CommandCompletedPayload;
import com.fuyun.iot.cache.IotSeqGate;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.dto.ConfirmChallengeRequest;
import com.fuyun.iot.dto.IssueCommandRequest;
import com.fuyun.iot.entity.IotCommandLogEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.entity.IotProductCommandEntity;
import com.fuyun.iot.enums.CommandDeliverMode;
import com.fuyun.iot.enums.CommandSafetyLevel;
import com.fuyun.iot.enums.CommandStatus;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame.CommandResultFrame;
import com.fuyun.iot.mapper.IotCommandLogMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.mapper.IotProductCommandMapper;
import com.fuyun.iot.properties.CommandProperties;
import com.fuyun.iot.registry.CommandRef;
import com.fuyun.iot.registry.IotDeviceRegistry;
import com.fuyun.iot.registry.RegistryException;
import com.fuyun.iot.vo.CommandLogVO;
import com.fuyun.iot.vo.ConfirmChallengeVO;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 命令下发编排器（FU-M14-09 五步下发唯一执行点，P2 PR-2 Task 8）：
 * ①RBAC+数据范围校验（操作者上下文由服务层透传 + 设备档案必须存在且已编病区——病区归属为
 * P0 数据范围形态，M01 RBAC 实装后收口为病区权限比对）→ ②设备在线预检（Redis 快照
 * fy:iot:snapshot:device-status:{deviceId}，Task 5 状态机写入；凭证签发侧仅 ONLINE 拒离线/
 * 未知，下发侧 ONLINE→SYNC、OFFLINE→ASYNC、未知→IOT-1014）→ ③二次确认凭证（签发
 * challengeId+预占命令号 TTL 120s；下发 GETDEL 一次性消费，缺失/过期/已用/载荷不一致
 * IOT-1015）→ ④Registry.sendCommand（在线同步=CompletableFuture 有界等待回执更新终态，超时
 * 置 TIMEOUT；离线异步=受理后置 ISSUED 待结果帧）→ ⑤结果回推（回执/结果帧经
 * {@link IotCommandLogMapper#casTerminal} 终态迁移并在同一写事务发布 iot.command.completed，
 * AFTER_COMMIT 出 MQ）。
 *
 * <p>白名单红线（14-iot 模块红线 3 / 总 Spec D8）：iot_product_command.allowed=false 默认拒
 * （IOT-1014）；治疗级豁免=配置 fuyun.iot.command.treatment-allowed=true 显式开启，且每次
 * 实际放行 warn 告警并以 iot_command_log 行留痕（safety_level=TREATMENT + M01 审计切面双留痕；
 * 升级审批面缺位注记 GC17，归 Task 18）。签发与下发两侧各自复检门槛（签发后白名单/豁免开关
 * 可能翻转），下发侧为最终裁决面。
 *
 * <p>等待形态（Task 8 实测申报）：P0 仓库无既有 AMQP 同步等待先例，按 brief 允许形态采用
 * CompletableFuture 有界等待（禁 Thread.sleep 轮询）——Registry.sendCommand 提交至模块内
 * 守护线程池，调用线程 get(超时) 有界阻塞；超时尽力 cancel（SDK 内部资源自行收尾）。
 *
 * <p>事务边界：门槛校验/凭证消费/落行为单语句自动提交，注册中心外呼一律在事务外（禁把外部
 * 慢调用裹进本地事务，ProductServiceImpl 先例）；终态 CAS 与事件发布经 TransactionTemplate
 * 同事务承载（AFTER_COMMIT 语义：回滚事务不发布）——不挂 @Transactional 方法级事务以规避
 * 编排器自调用代理失效。归 internal/ 包：模块内编排设施禁外引（宪法 B.1），装配归 IotConfig
 * @Import；JaCoCo 核心包规则成员，单测全覆盖。
 */
@Slf4j
public class CommandDispatcher {

    /** 二次确认凭证键前缀：fy:iot:cmd:challenge:（A.5-1 命名，拼 challengeId 为完整键） */
    static final String CHALLENGE_KEY_PREFIX = "fy:iot:cmd:challenge:";

    /** 凭证 TTL：120 秒（brief 冻结值；过期由 Redis 兜底，缺失/过期/已用统一 IOT-1015） */
    static final Duration CHALLENGE_TTL = Duration.ofSeconds(120);

    /** 设备在线快照键前缀：与 DeviceStatusServiceImpl 写入侧（Task 5）同键同源 */
    private static final String SNAPSHOT_KEY_PREFIX = "fy:iot:snapshot:device-status:";

    /** 错误消息截断上限：error_msg 列宽 VARCHAR(500)（列宽防线，防摘要超长致落库失败） */
    private static final int ERROR_MSG_MAX_LENGTH = 500;

    /** 审计留痕系统操作人（无登录上下文回退值，与审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    private final IotDeviceMapper deviceMapper;

    private final IotProductCommandMapper commandMapper;

    private final IotCommandLogMapper commandLogMapper;

    private final StringRedisTemplate redisTemplate;

    private final IotDeviceRegistry registry;

    private final IotSeqGate seqGate;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final ObjectMapper objectMapper;

    private final CommandProperties properties;

    /**
     * 同步命令守护线程池：Registry.sendCommand 的执行载体（有界等待的提交面）。守护线程随
     * JVM 退出、空闲 60s 自回收；命令下发为低频管理面操作，缓存池不设上界（拒绝策略不适用）。
     */
    private final ExecutorService deliveryExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "iot-cmd-delivery");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；TransactionTemplate 为 Boot
     * 事务自动配置 Bean，终态 CAS 与事件发布的同事务原子性载体）。
     *
     * @param deviceMapper     设备档案 mapper，非空；门槛存在性与病区归属取数
     * @param commandMapper    命令白名单 mapper，非空；allowed/safety_level 闸门取数
     * @param commandLogMapper 命令日志 mapper，非空；落行与状态机 CAS 通道
     * @param redisTemplate    String 模板（禁 JDK 序列化），非空；凭证签发/消费与在线快照预检
     * @param registry         设备注册中心契约，非空；命令下发唯一出口（双实现经 IotRegistryConfig）
     * @param seqGate          业务号发号器，非空；命令号 CMD 预占出口
     * @param events           Spring 事件发布器，非空；命令完成事件事务内发布入口
     * @param transactions     事务模板，非空；终态 CAS+发布同事务承载
     * @param objectMapper     JSON 转换器，非空；凭证载荷/参数快照/在线快照解析
     * @param properties       命令配置属性，非空；同步超时与治疗级豁免开关
     */
    @Autowired
    public CommandDispatcher(
            IotDeviceMapper deviceMapper,
            IotProductCommandMapper commandMapper,
            IotCommandLogMapper commandLogMapper,
            StringRedisTemplate redisTemplate,
            IotDeviceRegistry registry,
            IotSeqGate seqGate,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            CommandProperties properties) {
        this.deviceMapper = deviceMapper;
        this.commandMapper = commandMapper;
        this.commandLogMapper = commandLogMapper;
        this.redisTemplate = redisTemplate;
        this.registry = registry;
        this.seqGate = seqGate;
        this.events = events;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 下发门槛裁决（签发/下发两侧共用的复检点）：设备档案存在性（404）→ 病区归属（数据范围，
     * IOT-1014）→ 白名单默认拒（IOT-1014）→ 治疗级豁免闸门（IOT-1014）。
     *
     * @param deviceId    目标设备号，非空
     * @param commandName 命令名称，非空
     * @return 门槛裁决产物（设备档案 + 白名单行），非空
     * @throws BizException IOT-1006（404 设备不存在）/ IOT-1014（409 未编病区、白名单默认拒、
     *                      治疗级未豁免）
     */
    public GateDecision checkGates(String deviceId, String commandName) {
        // 数据库读操作：设备档案精确投影（productId 供白名单定位、wardId 供数据范围、status 留痕）
        IotDeviceEntity device = deviceMapper.selectOne(Wrappers.<IotDeviceEntity>lambdaQuery()
                .eq(IotDeviceEntity::getDeviceId, deviceId)
                .select(
                        IotDeviceEntity::getDeviceId,
                        IotDeviceEntity::getProductId,
                        IotDeviceEntity::getWardId,
                        IotDeviceEntity::getStatus));
        if (device == null) {
            throw new BizException(IotErrorCode.DEVICE_NOT_FOUND, HttpStatus.NOT_FOUND, "设备不存在：" + deviceId);
        }
        // 数据范围（P0 形态）：未编病区设备不开放远程命令（病区归属即操作范围，RBAC 收口归 M01）
        if (device.getWardId() == null) {
            log.warn("命令门槛拒绝（设备未编病区，数据范围不通过）：deviceId={}", deviceId);
            throw new BizException(IotErrorCode.COMMAND_NOT_ALLOWED, HttpStatus.CONFLICT, "设备未编病区，不允许远程命令：" + deviceId);
        }
        // 白名单默认拒：iot_product_command 登记行存在且 allowed=true 方放行（行缺失同拒）
        IotProductCommandEntity whitelist = commandMapper.selectOne(Wrappers.<IotProductCommandEntity>lambdaQuery()
                .eq(IotProductCommandEntity::getProductId, device.getProductId())
                .eq(IotProductCommandEntity::getCommandName, commandName));
        if (whitelist == null || Boolean.FALSE.equals(whitelist.getAllowed())) {
            log.warn(
                    "命令门槛拒绝（白名单默认拒）：deviceId={}，productId={}，commandName={}",
                    deviceId,
                    device.getProductId(),
                    commandName);
            throw new BizException(
                    IotErrorCode.COMMAND_NOT_ALLOWED, HttpStatus.CONFLICT, "命令不在白名单或未放行，不允许下发：" + commandName);
        }
        // 治疗级闸门：TREATMENT 须豁免开关显式开启（每次实际放行的 warn 告警在 dispatch 使用点）
        if (whitelist.getSafetyLevel() == CommandSafetyLevel.TREATMENT && !properties.treatmentAllowed()) {
            log.warn("命令门槛拒绝（治疗级未豁免）：deviceId={}，commandName={}", deviceId, commandName);
            throw new BizException(
                    IotErrorCode.COMMAND_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "治疗级命令未豁免（fuyun.iot.command.treatment-allowed=false），不允许下发：" + commandName);
        }
        return new GateDecision(device, whitelist);
    }

    /**
     * 读设备在线快照（Task 5 状态机写入面）：快照缺失/TTL 过期/损坏/状态值域外统一返回 null
     * （未知态），不反推档案状态（快照与库可能短暂不一致，预检以快照为唯一口径）。
     *
     * @param deviceId 设备号，非空
     * @return 快照状态（五态值域）；快照不可判为 null
     */
    public DeviceStatus readSnapshotStatus(String deviceId) {
        try {
            String json = redisTemplate.opsForValue().get(SNAPSHOT_KEY_PREFIX + deviceId);
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(json);
            String code = node.path("status").asText(null);
            return code == null ? null : DeviceStatus.fromCode(code);
        } catch (Exception e) {
            // 缓存读失败/损坏按未知态降级（快照可由下一状态帧重建，不阻断门槛链）
            log.warn("设备在线快照读取失败（按未知态处置）：deviceId={}，原因={}", deviceId, e.getMessage());
            return null;
        }
    }

    /**
     * 签发二次确认凭证（步骤③签发侧，五步顺序前置面）：门槛复检 → 在线预检（仅 ONLINE，离线
     * 拒签）→ 预占命令号 → 凭证载荷落 Redis（TTL 120s）。凭证为一次性消费形态：下发 GETDEL
     * 后即失效，载荷绑定设备/命令/参数防换目标与偷换参数。
     *
     * @param request 签发请求（@Valid 后置校验已过），非空
     * @return 凭证出网视图（challengeId/commandNo/expiresIn），非空
     * @throws BizException IOT-1006（404）/ IOT-1014（409 病区、白名单、治疗级、离线或未知态）
     */
    public ConfirmChallengeVO issueChallenge(ConfirmChallengeRequest request) {
        checkGates(request.deviceId(), request.commandName());
        // 离线拒签：凭证面向"即将下发"的操作确认，离线/未知态设备不给二次确认机会（下发侧
        // OFFLINE→ASYNC 仅承接"签发后设备掉线"的时序窗口，见 dispatch）
        DeviceStatus snapshotStatus = readSnapshotStatus(request.deviceId());
        if (snapshotStatus != DeviceStatus.ONLINE) {
            log.warn("凭证签发拒绝（设备不在线或状态未知）：deviceId={}，snapshotStatus={}", request.deviceId(), snapshotStatus);
            throw new BizException(
                    IotErrorCode.COMMAND_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "设备不在线或在线状态未知，命令不允许下发：" + request.deviceId());
        }
        // 预占命令号：签发即取号（CMD{yyyyMMdd}{%05d}），下发落行沿用，前端可先行展示
        String commandNo = seqGate.nextCommandNo();
        String challengeId = UUID.randomUUID().toString();
        String payload =
                toJson(new ChallengePayload(commandNo, request.deviceId(), request.commandName(), request.params()));
        // 缓存写操作：凭证载荷 TTL 120s（禁无 TTL 键红线）；StringRedisTemplate 承载
        redisTemplate.opsForValue().set(CHALLENGE_KEY_PREFIX + challengeId, payload, CHALLENGE_TTL);
        log.info(
                "二次确认凭证已签发：challengeId={}，commandNo={}，deviceId={}，commandName={}，expiresIn={}s",
                challengeId,
                commandNo,
                request.deviceId(),
                request.commandName(),
                ConfirmChallengeVO.EXPIRES_IN_SECONDS);
        return new ConfirmChallengeVO(challengeId, commandNo, ConfirmChallengeVO.EXPIRES_IN_SECONDS);
    }

    /**
     * 命令下发编排（五步顺序执行点）：凭证一次性消费（③）→ 门槛复检（①）→ 在线预检选道（②）
     * → 落 ISSUED 行并下发（④）→ 终态迁移与事件发布（⑤）。
     *
     * <p>选道口径（任务口径申报）：快照 ONLINE→SYNC 同步等待回执（超时 TIMEOUT）；OFFLINE→
     * ASYNC 受理后置 ISSUED 待结果帧回推（承接签发后设备掉线窗口，送达时间不可控）；快照缺失/
     * 其他状态→IOT-1014。操作者为空回退 system（审计留痕同源）。
     *
     * @param request 下发请求（必携凭证，@Valid 已过），非空
     * @param operator 操作人（服务层自操作者上下文解析），非空
     * @param traceId  全链路追踪号（服务层自 MDC 捕获），可空
     * @return 命令日志出网视图（终态或 ISSUED），非空
     * @throws BizException IOT-1015（400 凭证无效/不一致）/ IOT-1014（409 门槛或离线未知态）/
     *                      IOT-1022（503 注册中心不可用，行已置 FAILED）
     */
    public CommandLogVO dispatch(IssueCommandRequest request, String operator, String traceId) {
        String resolvedOperator = operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
        // 步骤③：凭证一次性消费（GETDEL）+ 防换目标一致性校验
        ChallengePayload payload = consumeChallenge(request.challengeId());
        requireChallengeMatches(payload, request);
        // 步骤①：门槛复检（签发后白名单/豁免开关可能翻转，下发侧为最终裁决面）
        GateDecision gate = checkGates(payload.deviceId(), payload.commandName());
        CommandSafetyLevel safetyLevel = gate.whitelist().getSafetyLevel();
        if (safetyLevel == CommandSafetyLevel.TREATMENT) {
            // 治疗级豁免使用点告警（红线条目：每次放行必告警；升级审批面缺位注记 GC17 归 Task 18）
            log.warn(
                    "治疗级命令经豁免开关放行（升级审批面缺位，GC17 归 Task 18）：commandNo={}，deviceId={}，commandName={}，operator={}",
                    payload.commandNo(),
                    payload.deviceId(),
                    payload.commandName(),
                    resolvedOperator);
        }
        // 步骤②：在线预检选道
        CommandDeliverMode mode = resolveDeliverMode(readSnapshotStatus(payload.deviceId()));
        // 步骤④：落 ISSUED 行（全要素留痕）后下发
        IotCommandLogEntity row =
                openCommandLog(payload, request.challengeId(), safetyLevel, mode, resolvedOperator, traceId);
        if (mode == CommandDeliverMode.SYNC) {
            return deliverSync(row, payload.params(), resolvedOperator, traceId);
        }
        return deliverAsync(row, payload.params(), resolvedOperator, traceId);
    }

    /**
     * 命令结果回推消费（步骤⑤结果帧面，IotDeviceCommandListener 唯一入口）：按设备定位最早
     * 未终态异步行（IoTDA 离线命令 FIFO 送达序）→ DELIVERED 中间态或终态迁移 + 事件发布。
     * 未知命令容错跳过（同状态帧口径，不产生副作用）；at-least-once 重投由 CAS 幂等兜底
     * （已终态零行不重发事件）。
     *
     * @param frame 命令状态帧解析产物（TelemetryFrameParser 第五形态），非空
     */
    public void completeFromResultFrame(CommandResultFrame frame) {
        IotCommandLogEntity outstanding = commandLogMapper.selectOutstandingAsync(frame.deviceId());
        if (outstanding == null) {
            // 容错口径：无未终态异步行（已终态/未知命令/同步行）的结果帧跳过不报错
            log.info(
                    "命令结果帧跳过（设备无未终态异步行）：deviceId={}，commandId={}，status={}",
                    frame.deviceId(),
                    frame.commandId(),
                    frame.registryStatus());
            return;
        }
        CommandStatus mapped = mapRegistryStatus(frame.registryStatus());
        if (mapped == CommandStatus.DELIVERED) {
            // 送达中间态：设备确认收到（结果帧 DELIVERED 驱动，非终态不发布事件）
            commandLogMapper.casDelivered(outstanding.getCommandNo());
            log.info(
                    "命令已送达待执行：commandNo={}，deviceId={}，commandId={}",
                    outstanding.getCommandNo(),
                    frame.deviceId(),
                    frame.commandId());
            return;
        }
        // 终态帧：失败摘要取结果原文（SUCCESS 为 null），结果时刻=帧时点
        String errorMsg = mapped == CommandStatus.SUCCESS
                ? null
                : firstNonBlank(frame.result(), "命令执行失败：" + frame.registryStatus());
        completeTerminal(
                outstanding,
                mapped,
                OffsetDateTime.ofInstant(frame.occurredAt(), ZoneOffset.UTC),
                errorMsg,
                outstanding.getOperator(),
                outstanding.getTraceId());
    }

    /**
     * 同步下发（步骤④在线同步）：Registry.sendCommand 提交守护线程池后有界等待回执——回执
     * 正常返回=设备已执行（同步命令面语义）置 SUCCESS；等待超时置 TIMEOUT 并 warn 告警；受理
     * 异常置 FAILED 并向外转 IOT-1022。终态迁移与事件发布同事务（TransactionTemplate）。
     *
     * @param row        已落库命令行（ISSUED），非空
     * @param params     下发参数，可空
     * @param operator   操作人，非空
     * @param traceId    追踪号，可空
     * @return 终态命令视图，非空（SUCCESS/TIMEOUT）
     * @throws BizException IOT-1022（503 受理失败，行已置 FAILED）
     */
    private CommandLogVO deliverSync(
            IotCommandLogEntity row, Map<String, Object> params, String operator, String traceId) {
        Future<CommandRef> pending =
                deliveryExecutor.submit(() -> registry.sendCommand(row.getDeviceId(), row.getCommandName(), params));
        try {
            // 有界等待回执（禁 Thread.sleep 轮询；CompletableFuture 家族形态——超时即完成判定）
            pending.get(properties.syncTimeout().toMillis(), TimeUnit.MILLISECONDS);
            completeTerminal(row, CommandStatus.SUCCESS, OffsetDateTime.now(), null, operator, traceId);
            return CommandLogVO.from(row, objectMapper);
        } catch (TimeoutException e) {
            // 尽力取消阻塞中的 SDK 调用（SDK 内部资源自行收尾；行已 TIMEOUT，迟滞回执由 CAS 零行兜底）
            pending.cancel(true);
            String reason = "同步等待回执超时（" + properties.syncTimeout().toSeconds() + "s）";
            log.warn(
                    "同步命令等待回执超时，置 TIMEOUT：commandNo={}，deviceId={}，timeout={}ms",
                    row.getCommandNo(),
                    row.getDeviceId(),
                    properties.syncTimeout().toMillis());
            completeTerminal(row, CommandStatus.TIMEOUT, OffsetDateTime.now(), reason, operator, traceId);
            return CommandLogVO.from(row, objectMapper);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error(
                    "命令下发受理失败：commandNo={}，deviceId={}，原因={}",
                    row.getCommandNo(),
                    row.getDeviceId(),
                    cause.getMessage(),
                    cause);
            completeTerminal(
                    row, CommandStatus.FAILED, OffsetDateTime.now(), truncate(cause.getMessage()), operator, traceId);
            throw new BizException(
                    IotErrorCode.REGISTRY_UNAVAILABLE,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "注册中心不可用，命令下发失败：" + row.getDeviceId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            log.warn("命令下发等待被中断：commandNo={}，deviceId={}", row.getCommandNo(), row.getDeviceId());
            completeTerminal(row, CommandStatus.FAILED, OffsetDateTime.now(), "命令下发等待被中断", operator, traceId);
            throw new BizException(
                    IotErrorCode.REGISTRY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "命令下发被中断：" + row.getCommandNo());
        }
    }

    /**
     * 异步下发（步骤④离线异步）：注册中心受理（离线设备由平台缓存待上线送达）后维持 ISSUED
     * 待结果帧；受理失败置 FAILED 并转 IOT-1022。终态由 {@link #completeFromResultFrame} 回推。
     *
     * @param row        已落库命令行（ISSUED/ASYNC），非空
     * @param params     下发参数，可空
     * @param operator   操作人，非空
     * @param traceId    追踪号，可空
     * @return ISSUED 态命令视图，非空
     * @throws BizException IOT-1022（503 受理失败，行已置 FAILED）
     */
    private CommandLogVO deliverAsync(
            IotCommandLogEntity row, Map<String, Object> params, String operator, String traceId) {
        try {
            registry.sendCommand(row.getDeviceId(), row.getCommandName(), params);
        } catch (RegistryException e) {
            log.error(
                    "异步命令受理失败：commandNo={}，deviceId={}，原因={}",
                    row.getCommandNo(),
                    row.getDeviceId(),
                    e.getMessage(),
                    e);
            completeTerminal(
                    row, CommandStatus.FAILED, OffsetDateTime.now(), truncate(e.getMessage()), operator, traceId);
            throw new BizException(
                    IotErrorCode.REGISTRY_UNAVAILABLE,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "注册中心不可用，命令下发失败：" + row.getDeviceId());
        }
        log.info(
                "异步命令已受理，置 ISSUED 待结果帧回推：commandNo={}，deviceId={}，operator={}",
                row.getCommandNo(),
                row.getDeviceId(),
                operator);
        return CommandLogVO.from(row, objectMapper);
    }

    /**
     * 终态迁移 + 事件发布（步骤⑤共用出口，同步回执/超时/受理失败/结果帧四路）：CAS 显式
     * deleted=0 限定旧状态（终态不可变更红线——并发回执与结果帧竞态仅首个迁移方生效并发布）；
     * 同一写事务内 publishEvent（IotDomainPublisher AFTER_COMMIT 出 MQ，回滚事务不发布）。
     *
     * @param row       命令行（本地视图随终态同步），非空
     * @param terminal  终态（SUCCESS/FAILED/TIMEOUT），非空
     * @param resultAt  结果时刻，非空
     * @param errorMsg  失败原因，可空（SUCCESS 为 null）
     * @param operator  操作人/留痕主体，非空
     * @param traceId   追踪号（事件透传），可空
     */
    private void completeTerminal(
            IotCommandLogEntity row,
            CommandStatus terminal,
            OffsetDateTime resultAt,
            String errorMsg,
            String operator,
            String traceId) {
        // 数据库写操作：终态 CAS + 同事务事件发布（TransactionTemplate 承载 AFTER_COMMIT 语义）
        transactions.execute(txStatus -> {
            int updated =
                    commandLogMapper.casTerminal(row.getCommandNo(), terminal.getCode(), resultAt, errorMsg, operator);
            if (updated == 0) {
                // CAS 零行：行已终态或不存在（重投结果帧/迟滞回执先行）——不重发事件
                log.warn("命令终态迁移未命中（已终态或行不存在），跳过事件发布：commandNo={}，target={}", row.getCommandNo(), terminal);
                return Boolean.FALSE;
            }
            // 消息发送：同一写事务内发布命令完成事件（M05/M16 回写执行结果与告知上下文的消费源）
            events.publishEvent(new IotDomainEvent(
                    IotMessagingConstants.EVENT_COMMAND_COMPLETED,
                    new CommandCompletedPayload(
                            row.getCommandNo(),
                            row.getDeviceId(),
                            row.getCommandName(),
                            terminal.getCode(),
                            operator,
                            resultAt.toInstant(),
                            errorMsg),
                    Instant.now(),
                    traceId));
            return Boolean.TRUE;
        });
        // 本地视图与库内终态同步（VO 出网口径一致）
        row.setStatus(terminal);
        row.setResultAt(resultAt);
        row.setErrorMsg(errorMsg);
        log.info(
                "命令终态迁移完成：commandNo={}，deviceId={}，status={}，resultAt={}，errorMsg={}",
                row.getCommandNo(),
                row.getDeviceId(),
                terminal,
                resultAt,
                errorMsg);
    }

    /**
     * 凭证一次性消费：Redis GETDEL 原子取出并删除（同 challengeId 二次消费必空）——缺失/过期
     * （TTL 兜底）/已用统一 IOT-1015；载荷损坏同判（毒化凭证不可下发）。
     *
     * @param challengeId 凭证标识，非空
     * @return 凭证载荷，非空
     * @throws BizException IOT-1015（400）
     */
    private ChallengePayload consumeChallenge(String challengeId) {
        // 缓存操作：GETDEL 一次性消费（ValueOperations.getAndDelete，Redis ≥6.2 原子命令；
        // 实测 spring-data-redis 3.5.11 与 redis:8.10.1 双侧支持）
        String json = redisTemplate.opsForValue().getAndDelete(CHALLENGE_KEY_PREFIX + challengeId);
        if (json == null || json.isBlank()) {
            log.warn("二次确认凭证无效（缺失/过期/已用）：challengeId={}", challengeId);
            throw new BizException(
                    IotErrorCode.COMMAND_CONFIRM_INVALID, HttpStatus.BAD_REQUEST, "二次确认凭证无效（缺失/过期/已用）：" + challengeId);
        }
        try {
            return objectMapper.readValue(json, ChallengePayload.class);
        } catch (Exception e) {
            log.warn("二次确认凭证载荷损坏：challengeId={}，原因={}", challengeId, e.getMessage());
            throw new BizException(
                    IotErrorCode.COMMAND_CONFIRM_INVALID, HttpStatus.BAD_REQUEST, "二次确认凭证无效（载荷损坏）：" + challengeId);
        }
    }

    /**
     * 凭证与下发请求一致性校验（防换目标/偷换参数）：设备/命令严格相等，参数按 JSON 树语义
     * 等价（键序无关）；不一致 IOT-1015。
     *
     * @param payload 凭证载荷，非空
     * @param request 下发请求，非空
     * @throws BizException IOT-1015（400）
     */
    private void requireChallengeMatches(ChallengePayload payload, IssueCommandRequest request) {
        boolean matches = payload.deviceId().equals(request.deviceId())
                && payload.commandName().equals(request.commandName())
                && paramsEquivalent(payload.params(), request.params());
        if (!matches) {
            log.warn(
                    "凭证与下发请求不一致（防换目标拦截）：challengeDevice={}，requestDevice={}，challengeCommand={}，requestCommand={}",
                    payload.deviceId(),
                    request.deviceId(),
                    payload.commandName(),
                    request.commandName());
            throw new BizException(
                    IotErrorCode.COMMAND_CONFIRM_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "二次确认凭证与下发请求不一致：" + request.challengeId());
        }
    }

    /**
     * 参数语义等价：null 与空对象视为同义，其余按 JSON 树比对（键序无关）。
     *
     * @param left  凭证载荷参数，可空
     * @param right 下发请求参数，可空
     * @return true=语义等价
     */
    private boolean paramsEquivalent(Map<String, Object> left, Map<String, Object> right) {
        try {
            JsonNode leftTree = objectMapper.readTree(toJson(left == null ? Map.of() : left));
            JsonNode rightTree = objectMapper.readTree(toJson(right == null ? Map.of() : right));
            return leftTree.equals(rightTree);
        } catch (Exception e) {
            // 序列化失败按不等价保守处置（拒下发优于放行）
            log.warn("凭证参数比对失败（按不一致处置）：原因={}", e.getMessage());
            return false;
        }
    }

    /**
     * 在线预检选道（步骤②下发侧）：ONLINE→SYNC；OFFLINE→ASYNC；其余（缺失/未知/异常态）→
     * IOT-1014。
     *
     * @param snapshotStatus 快照状态，可空（未知）
     * @return 下发通道，非空
     * @throws BizException IOT-1014（409）
     */
    private CommandDeliverMode resolveDeliverMode(DeviceStatus snapshotStatus) {
        if (snapshotStatus == DeviceStatus.ONLINE) {
            return CommandDeliverMode.SYNC;
        }
        if (snapshotStatus == DeviceStatus.OFFLINE) {
            // 离线走异步：受理后置 ISSUED 待结果帧（送达时间不可控，14-iot FU-M14-09）
            return CommandDeliverMode.ASYNC;
        }
        log.warn("下发预检拒绝（设备不在线或状态未知）：snapshotStatus={}", snapshotStatus);
        throw new BizException(IotErrorCode.COMMAND_NOT_ALLOWED, HttpStatus.CONFLICT, "设备不在线或在线状态未知，命令不允许下发");
    }

    /**
     * 命令日志行落库（步骤④前半，状态机初态）：全要素留痕（参数快照/安全等级/凭证引用/通道/
     * 操作者/追踪号），初态 ISSUED。
     *
     * @param payload      凭证载荷（命令要素与预占命令号），非空
     * @param challengeId  凭证标识（confirm_ref 留痕），非空
     * @param safetyLevel  安全等级（白名单快照），非空
     * @param mode         下发通道（预检选道），非空
     * @param operator     操作人，非空
     * @param traceId      追踪号，可空
     * @return 已落库命令行，非空
     */
    private IotCommandLogEntity openCommandLog(
            ChallengePayload payload,
            String challengeId,
            CommandSafetyLevel safetyLevel,
            CommandDeliverMode mode,
            String operator,
            String traceId) {
        IotCommandLogEntity row = new IotCommandLogEntity();
        row.setCommandNo(payload.commandNo());
        row.setDeviceId(payload.deviceId());
        row.setCommandName(payload.commandName());
        row.setCommandParams(toJson(payload.params()));
        row.setSafetyLevel(safetyLevel);
        row.setOperator(operator);
        row.setConfirmRef(challengeId);
        row.setDeliverMode(mode);
        row.setStatus(CommandStatus.ISSUED);
        row.setTraceId(traceId);
        // 数据库写操作：命令行落库（雪花 ID 由 MP ASSIGN_ID 生成，issued_at 由 DB DEFAULT 维护）
        commandLogMapper.insert(row);
        log.info(
                "命令日志行已落库：commandNo={}，deviceId={}，commandName={}，safetyLevel={}，deliverMode={}，operator={}，confirmRef={}",
                row.getCommandNo(),
                row.getDeviceId(),
                row.getCommandName(),
                safetyLevel,
                mode,
                operator,
                challengeId);
        return row;
    }

    /**
     * 注册中心命令状态 → 本地命令状态机映射（值域已由解析器毒丸校验）：DELIVERED 保留中间态；
     * SUCCESS 保留；TIMEOUT/EXPIRED 归 TIMEOUT（平台缓存过期=未送达超时语义）；FAILED/REMOVED
     * 归 FAILED。
     *
     * @param registryStatus 帧状态原值（IOTDA_COMMAND_STATUSES 值域），非空
     * @return 本地命令状态，非空
     */
    private static CommandStatus mapRegistryStatus(String registryStatus) {
        return switch (registryStatus) {
            case "DELIVERED" -> CommandStatus.DELIVERED;
            case "SUCCESS" -> CommandStatus.SUCCESS;
            case "TIMEOUT", "EXPIRED" -> CommandStatus.TIMEOUT;
            default -> CommandStatus.FAILED;
        };
    }

    /** JSON 序列化（参数快照/凭证载荷共用；null 参数出 null） */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // 参数不可序列化属程序缺陷：留痕缺失比误下发可接受，落行置 null 并告警
            log.warn("命令参数 JSON 序列化失败（快照置空）：原因={}", e.getMessage());
            return null;
        }
    }

    /** 错误消息截断到 error_msg 列宽防线（超长摘要收口，防落库失败丢终态） */
    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= ERROR_MSG_MAX_LENGTH ? text : text.substring(0, ERROR_MSG_MAX_LENGTH);
    }

    /** 空白回退：第一个非空白文本（结果帧失败摘要缺省兜底） */
    private static String firstNonBlank(String left, String fallback) {
        return left != null && !left.isBlank() ? left : fallback;
    }

    /**
     * 下发门槛裁决产物（checkGates 出参，record 不可变）。
     *
     * @param device    设备档案（已过存在性与病区归属），非空
     * @param whitelist 白名单行（已过 allowed 与豁免闸门），非空
     */
    public record GateDecision(IotDeviceEntity device, IotProductCommandEntity whitelist) {}

    /**
     * 二次确认凭证载荷（Redis 值载体，record 不可变； Jackson 反序列化经 -parameters 记名）。
     *
     * @param commandNo   预占命令号，非空
     * @param deviceId    绑定设备号，非空
     * @param commandName 绑定命令名，非空
     * @param params      绑定参数，可空
     */
    record ChallengePayload(String commandNo, String deviceId, String commandName, Map<String, Object> params) {}
}
