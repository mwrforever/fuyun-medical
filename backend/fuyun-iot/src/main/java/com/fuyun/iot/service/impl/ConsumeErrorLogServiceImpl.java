package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.dto.AbandonConsumeErrorRequest;
import com.fuyun.iot.dto.ConsumeErrorQueryRequest;
import com.fuyun.iot.entity.IotConsumeErrorLogEntity;
import com.fuyun.iot.enums.ConsumeErrorStage;
import com.fuyun.iot.enums.ConsumeErrorStatus;
import com.fuyun.iot.internal.IotDeviceCommandListener;
import com.fuyun.iot.internal.TelemetryFrameParser;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame;
import com.fuyun.iot.internal.alarm.AlarmEngine;
import com.fuyun.iot.mapper.IotConsumeErrorLogMapper;
import com.fuyun.iot.service.IConsumeErrorLogService;
import com.fuyun.iot.service.IDeviceStatusService;
import com.fuyun.iot.service.ITelemetryIngestService;
import com.fuyun.iot.vo.ConsumeErrorVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 消费错误日志服务实现（iot.iot_consume_error_log 写入口与处置面，BRIEF-PR4-01 §3 service 行，
 * P2 PR-2 Task 10 补齐 V401 P0 只写遗留义务的管理端重放/放弃）。
 *
 * <p>毒丸隔离优先于留痕（javadoc 契约声明，DeadLetterListener §5 口径同源）：留痕落库失败（含一切
 * 运行时异常）catch 全吞 + error 告警且不抛——若留痕失败向消费循环上抛，毒丸帧将无法被确认抛弃，
 * IoTDA 重推无限循环与隔离目标相悖；牺牲单帧留痕换取消费链路存活，error 日志即为人工兜底告警。
 *
 * <p>留痕口径（V401 列宽防线）：raw_digest = 原文 SHA-256 十六进制小写 64 位（DeadLetterListener
 * 同口径）；raw_payload = 载荷 4000 字符截断引用（调用方须已脱敏，禁原文敏感值全量入库）；
 * error_msg = 500 字符截断。
 *
 * <p>处置状态机（V401 头申报）：PENDING → REPLAYED / ABANDONED。重放 = CAS 认领（PENDING/
 * REPLAYED → REPLAYED，replay_count 累加）后按 {@link ParsedFrame} sealed 五形态重新入解析管道
 * （{@link TelemetryFrameParser#parse} → 遥测帧批量入库/状态帧即时处理/告警帧透传评估/命令结果
 * 回推终态，与 IotAmqpTelemetryConsumer 消费分派同构）。认领经 {@link TransactionTemplate} 独立
 * 提交单元（round 1 Important-2 修复：方法级事务会在管道异常时默认回滚认领，与「记录已标记
 * REPLAYED 供追溯」契约相悖——提交单元切分后管道失败不回滚已提交认领，异常借承 IOT-1021 翻译
 * 上抛，AlarmServiceImpl 借承申报先例）。放弃 = 仅 PENDING 行 CAS 迁移 ABANDONED 终态，原因
 * 追加承载于 error_msg（无独立原因列，500 截断防线）。
 *
 * <p>装配归 IotConfig @Import；JaCoCo 核心包（com.fuyun.iot.service.impl）LINE=1.00 成员。
 */
@Slf4j
public class ConsumeErrorLogServiceImpl implements IConsumeErrorLogService {

    /** 审计留痕系统操作人（无登录上下文回退值，与审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 放弃原因追加锚（V401 无独立原因列，原因以 error_msg 追加承载） */
    private static final String ABANDON_REASON_SUFFIX = "；放弃原因：";

    /** 消费错误台账 mapper：毒丸留痕唯一写通道与处置 CAS 通道 */
    private final IotConsumeErrorLogMapper consumeErrorLogMapper;

    /** 遥测入库服务：重放遥测/推送展开帧的批量入库执行点（解析管道下游） */
    private final ITelemetryIngestService telemetryIngestService;

    /** 设备状态服务：重放状态帧的即时处理执行点（返回档案 wardId，重放场景忽略返回值） */
    private final IDeviceStatusService deviceStatusService;

    /** 告警引擎：重放设备告警帧的透传评估执行点（独立短事务承载落行与事件发布） */
    private final AlarmEngine alarmEngine;

    /** 命令域监听器：重放命令结果帧的终态回推执行点 */
    private final IotDeviceCommandListener commandResultListener;

    /**
     * 事务模板：重放 CAS 认领的独立提交单元载体（Boot 事务自动配置供给，CommandDispatcher/
     * LinkageExecutor 同款注入形态）——认领提交即生效，管道失败不回滚（Important-2 修复）。
     */
    private final TransactionTemplate transactions;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param consumeErrorLogMapper   消费错误日志 mapper，非空；来源：同模块 mapper 包
     * @param telemetryIngestService  遥测入库服务，非空；来源：IotConfig 装配链
     * @param deviceStatusService     设备状态服务，非空；来源：IotConfig 装配链
     * @param alarmEngine             告警引擎，非空；来源：IotConfig 装配链
     * @param commandResultListener   命令域监听器，非空；来源：IotConfig 装配链
     * @param transactions            事务模板，非空；来源：Boot 事务自动配置（重放认领独立提交单元）
     */
    public ConsumeErrorLogServiceImpl(
            IotConsumeErrorLogMapper consumeErrorLogMapper,
            ITelemetryIngestService telemetryIngestService,
            IDeviceStatusService deviceStatusService,
            AlarmEngine alarmEngine,
            IotDeviceCommandListener commandResultListener,
            TransactionTemplate transactions) {
        this.consumeErrorLogMapper = consumeErrorLogMapper;
        this.telemetryIngestService = telemetryIngestService;
        this.deviceStatusService = deviceStatusService;
        this.alarmEngine = alarmEngine;
        this.commandResultListener = commandResultListener;
        this.transactions = transactions;
    }

    @Override
    public void recordParseFailure(String queueName, String rawText, String stage, String errorMsg) {
        String digest = sha256Hex(rawText);
        IotConsumeErrorLogEntity entity = new IotConsumeErrorLogEntity();
        entity.setQueueName(queueName);
        entity.setRawDigest(digest);
        // 载荷引用截断（raw_payload 列宽 VARCHAR(4000) 防线；脱敏责任在调用方）
        entity.setRawPayload(truncate(rawText, IotMessagingConstants.RAW_PAYLOAD_MAX_LENGTH));
        entity.setErrorStage(ConsumeErrorStage.fromCode(stage));
        entity.setErrorMsg(truncate(errorMsg, IotMessagingConstants.ERROR_MSG_MAX_LENGTH));
        entity.setStatus(ConsumeErrorStatus.PENDING);
        entity.setReplayCount(0);
        try {
            consumeErrorLogMapper.insert(entity);
        } catch (RuntimeException e) {
            // 毒丸隔离优先于留痕（类 javadoc 契约）：catch RuntimeException 兜底覆盖非 DB 意外，
            // error 日志即为告警通道，绝不向消费循环上抛
            log.error(
                    "消费错误留痕落库失败，该帧放弃留痕转人工排查：queue_name={}，stage={}，raw_digest={}，原因={}",
                    queueName,
                    stage,
                    digest,
                    e.getMessage(),
                    e);
            return;
        }
        // 不打印载荷原文防敏感信息入日志；库内留有截断引用与摘要，日志以队列/阶段/摘要定位
        log.info("消费错误留痕落库完成：queue_name={}，stage={}，raw_digest={}", queueName, stage, digest);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ConsumeErrorVO> page(ConsumeErrorQueryRequest request) {
        // 缺省补齐：page 0 基缺省 0、size 缺省 20（GET 幂等查询无强制必填面）
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();
        // 数据库读操作：过滤分页（idx_status_created 准入；id 倒序 = 新错误在前，A.4.3-17 唯一顺序）
        Page<IotConsumeErrorLogEntity> result = consumeErrorLogMapper.selectPage(
                new Page<>(page + 1L, size),
                Wrappers.<IotConsumeErrorLogEntity>lambdaQuery()
                        .eq(
                                request.queueName() != null
                                        && !request.queueName().isBlank(),
                                IotConsumeErrorLogEntity::getQueueName,
                                request.queueName())
                        .eq(request.status() != null, IotConsumeErrorLogEntity::getStatus, request.status())
                        .orderByDesc(IotConsumeErrorLogEntity::getErrorId));
        return PageResult.of(
                result.getRecords().stream().map(ConsumeErrorVO::from).toList(), page, size, result.getTotal());
    }

    @Override
    public ConsumeErrorVO replay(Long errorId) {
        IotConsumeErrorLogEntity row = requireErrorLog(errorId);
        if (row.getStatus() == ConsumeErrorStatus.ABANDONED) {
            // 已放弃为终态（V401 状态机）：禁再重放
            throw stateNotAllowed("已放弃记录禁止重放：" + errorId);
        }
        if (row.getRawPayload() == null || row.getRawPayload().isBlank()) {
            // 载荷引用缺失（载体不可读帧留痕为空引用）：无解析管道可入，借承 IOT-1021 显式拒绝
            throw stateNotAllowed("载荷引用缺失，无法重放：" + errorId);
        }
        String operator = currentOperator();
        // 数据库写操作：CAS 认领独立提交单元（PENDING/REPLAYED → REPLAYED + 计数累加）——
        // TransactionTemplate 提交即生效（CommandDispatcher 终态 CAS 先例），后续管道失败的
        // RuntimeException（含翻译后的 BizException）不再回滚已提交认领，「记录已标记 REPLAYED
        // 供追溯」契约由此真实成立；方法级 @Transactional 反而令管道异常默认回滚认领（round 1
        // Important-2 修复）
        Boolean claimed =
                transactions.execute(txStatus -> consumeErrorLogMapper.casMarkReplayed(errorId, operator) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            log.warn("重放 CAS 落败（并发竞争或状态已迁移）：errorId={}", errorId);
            throw stateNotAllowed("重放失败：记录已被并发处置或状态不允许：" + errorId);
        }
        // 重新入解析管道：parse → sealed 五形态分派（与消费者 dispatchSafely 同构，无 JMS 确认语义；
        // 遥测入库/状态处理/告警评估各自独立事务，不在本方法事务边界内）
        try {
            ParsedFrame frame = TelemetryFrameParser.parse(row.getRawPayload().getBytes(StandardCharsets.UTF_8));
            dispatchFrame(frame);
        } catch (RuntimeException e) {
            // 管道失败时认领已提交不可回滚：记录保持 REPLAYED 标记与累加后的 replay_count 供追溯，
            // 异常借承 IOT-1021 翻译上抛（AlarmServiceImpl 借承申报先例：消息显式区分"已标记 REPLAYED"场景）
            log.error(
                    "重放处理失败（记录已标记 REPLAYED 供追溯）：errorId={}，stage={}，原因={}",
                    errorId,
                    row.getErrorStage(),
                    e.getMessage(),
                    e);
            throw new BizException(
                    IotErrorCode.CONSUME_ERROR_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "重放处理失败（记录已标记 REPLAYED）：" + e.getMessage());
        }
        log.info(
                "消费错误重放完成：errorId={}，stage={}，replay_count={}，operator={}",
                errorId,
                row.getErrorStage(),
                row.getReplayCount() + 1,
                operator);
        return ConsumeErrorVO.from(requireErrorLog(errorId));
    }

    @Override
    @Transactional
    public ConsumeErrorVO abandon(Long errorId, AbandonConsumeErrorRequest request) {
        // 原因强制（V401 状态机申报 ABANDONED 必填原因）：空白原因在服务层显式拒（400 借承 IOT-1021）
        String reason = request == null ? null : request.reason();
        if (reason == null || reason.isBlank()) {
            throw new BizException(IotErrorCode.CONSUME_ERROR_STATE_NOT_ALLOWED, HttpStatus.BAD_REQUEST, "放弃原因强制，禁止空白");
        }
        IotConsumeErrorLogEntity row = requireErrorLog(errorId);
        String operator = currentOperator();
        // 原因追加承载于 error_msg（V401 无独立原因列；500 列宽截断防线）
        String mergedMsg = truncate(
                (row.getErrorMsg() == null ? "" : row.getErrorMsg()) + ABANDON_REASON_SUFFIX + reason,
                IotMessagingConstants.ERROR_MSG_MAX_LENGTH);
        // 数据库写操作：CAS 仅 PENDING 迁移 ABANDONED（已重放/已放弃/并发双弃零行拒绝）
        if (consumeErrorLogMapper.casMarkAbandoned(errorId, mergedMsg, operator) != 1) {
            log.warn("放弃 CAS 落败（仅 PENDING 可放弃或并发竞争）：errorId={}", errorId);
            throw stateNotAllowed("放弃失败：仅待处理记录可放弃，或已被并发处置：" + errorId);
        }
        log.info("消费错误放弃完成：errorId={}，operator={}，reason={}", errorId, operator, reason);
        return ConsumeErrorVO.from(requireErrorLog(errorId));
    }

    /**
     * sealed 五形态分派重放（与 IotAmqpTelemetryConsumer 消费分派同构）：遥测帧/推送展开帧入
     * 批量入库，状态帧即时处理，设备告警帧透传评估，命令结果帧回推终态（无 JMS 确认语义——
     * 重放为管理端显式动作，处理结果以返回视图与日志留痕）。入参仅来源于
     * {@link TelemetryFrameParser#parse} 产物（sealed 接口编译期穷尽五形态，词表外形态不可达
     * 本方法——解析器新增形态时由本链显式补接线，不设兜底暗道）。
     *
     * @param frame 解析产物，非空
     */
    private void dispatchFrame(ParsedFrame frame) {
        if (frame instanceof ParsedFrame.TelemetryFrame telemetryFrame) {
            telemetryIngestService.ingest(List.of(telemetryFrame.message()));
        } else if (frame instanceof ParsedFrame.TelemetryBatchFrame batchFrame) {
            telemetryIngestService.ingest(batchFrame.messages());
        } else if (frame instanceof ParsedFrame.StatusFrame statusFrame) {
            deviceStatusService.apply(statusFrame.event());
        } else if (frame instanceof ParsedFrame.DeviceAlarmFrame alarmFrame) {
            alarmEngine.evaluateDeviceAlarm(alarmFrame);
        } else {
            // 终态分支：命令结果帧回推（sealed 穷尽保证其余四形态已在上方分支消化）
            commandResultListener.onCommandResultFrame((ParsedFrame.CommandResultFrame) frame);
        }
    }

    /**
     * 定位错误行（404 唯一出口）。
     *
     * @param errorId 错误行 ID，非空
     * @return 错误日志实体，非空
     * @throws BizException IOT-1020（404；记录不存在）
     */
    private IotConsumeErrorLogEntity requireErrorLog(Long errorId) {
        IotConsumeErrorLogEntity row = consumeErrorLogMapper.selectById(errorId);
        if (row == null) {
            throw new BizException(IotErrorCode.CONSUME_ERROR_NOT_FOUND, HttpStatus.NOT_FOUND, "消费错误记录不存在：" + errorId);
        }
        return row;
    }

    /**
     * 构造 IOT-1021 业务异常（409，处置状态机违例统一出口）。
     *
     * @param message 违规原因描述，非空
     * @return 业务异常，非空
     */
    private static BizException stateNotAllowed(String message) {
        return new BizException(IotErrorCode.CONSUME_ERROR_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, message);
    }

    /**
     * 取当前处置操作人（无登录上下文回退 system，BindingServiceImpl 同款口径）。
     *
     * @return 操作人标识，非空
     */
    private static String currentOperator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }

    /**
     * 计算帧原文的 SHA-256 十六进制摘要（小写 64 位，与 raw_digest 列宽一致）。
     *
     * @param text 帧原文，非空
     * @return 64 位小写十六进制摘要
     */
    private static String sha256Hex(String text) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(IotMessagingConstants.DIGEST_ALGORITHM_SHA256);
            byte[] hashed = messageDigest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // 内部断言：SHA-256 为 JDK 内置算法，理论不可达（JDK 环境缺陷，非用户输入路径）；
            // 防御性包装为 ISE 使环境缺陷显性暴露
            throw new IllegalStateException("SHA-256 摘要算法不可用（JDK 环境异常）", e);
        }
    }

    /**
     * 列宽截断防线：超长文本截断至 maxCharacters，null 原样返回（可空列语义保留）。
     *
     * @param text          原文，可空
     * @param maxCharacters 最大保留字符数（DB 列宽）
     * @return 截断后的文本；入参为 null 返回 null
     */
    private static String truncate(String text, int maxCharacters) {
        if (text == null || text.length() <= maxCharacters) {
            return text;
        }
        return text.substring(0, maxCharacters);
    }
}
