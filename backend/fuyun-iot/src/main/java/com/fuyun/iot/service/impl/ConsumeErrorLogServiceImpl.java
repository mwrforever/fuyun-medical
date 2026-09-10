package com.fuyun.iot.service.impl;

import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotConsumeErrorLogEntity;
import com.fuyun.iot.enums.ConsumeErrorStage;
import com.fuyun.iot.enums.ConsumeErrorStatus;
import com.fuyun.iot.mapper.IotConsumeErrorLogMapper;
import com.fuyun.iot.service.IConsumeErrorLogService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.extern.slf4j.Slf4j;

/**
 * 消费错误日志服务实现（iot.iot_consume_error_log 写入口，BRIEF-PR4-01 §3 service 行）。
 *
 * <p>毒丸隔离优先于留痕（javadoc 契约声明，DeadLetterListener §5 口径同源）：落库失败（含一切
 * 运行时异常）catch 全吞 + error 告警且不抛——若留痕失败向消费循环上抛，毒丸帧将无法被确认抛弃，
 * IoTDA 重推无限循环与隔离目标相悖；牺牲单帧留痕换取消费链路存活，error 日志即为人工兜底告警。
 *
 * <p>留痕口径（V401 列宽防线）：raw_digest = 原文 SHA-256 十六进制小写 64 位（DeadLetterListener
 * 同口径）；raw_payload = 载荷 4000 字符截断引用（调用方须已脱敏，禁原文敏感值全量入库）；
 * error_msg = 500 字符截断；status 恒 PENDING、replay_count 恒 0（P0 只建写路径，处置随 P1）。
 * 装配归 IotConfig @Import；JaCoCo 核心包（com.fuyun.iot.service.impl）LINE=1.00 成员。
 */
@Slf4j
public class ConsumeErrorLogServiceImpl implements IConsumeErrorLogService {

    /** 消费错误台账 mapper：毒丸留痕唯一写通道 */
    private final IotConsumeErrorLogMapper consumeErrorLogMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param consumeErrorLogMapper 消费错误日志 mapper，非空；来源：同模块 mapper 包
     */
    public ConsumeErrorLogServiceImpl(IotConsumeErrorLogMapper consumeErrorLogMapper) {
        this.consumeErrorLogMapper = consumeErrorLogMapper;
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
            // SHA-256 为 JDK 内置算法，理论不可达；防御性包装为 IllegalState 使环境缺陷显性暴露
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
