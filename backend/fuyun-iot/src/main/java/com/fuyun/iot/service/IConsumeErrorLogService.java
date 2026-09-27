package com.fuyun.iot.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.AbandonConsumeErrorRequest;
import com.fuyun.iot.dto.ConsumeErrorQueryRequest;
import com.fuyun.iot.vo.ConsumeErrorVO;

/**
 * 消费错误日志服务：AMQP 主链路失败消息的毒丸留痕写入口与处置面（M14 FU-M14-01，V401 表）。
 *
 * <p>毒丸隔离口径：解析失败帧落本表（stage=PARSE）后确认抛弃，不阻塞消费队列；落库失败全吞
 * 仅告警（毒丸隔离优先于留痕，DeadLetterListener 同源语义）。处置状态机（V401 头申报）：
 * PENDING 待处理 → REPLAYED 已重放 / ABANDONED 已放弃；重放可多次（replay_count 累加），
 * 放弃为终态（禁再重放）。重放/放弃为 P0 只写遗留义务的管理端补齐（P2 PR-2 Task 10）。
 */
public interface IConsumeErrorLogService {

    /**
     * 留痕一次消费失败：原文 SHA-256 摘要 + 载荷脱敏截断引用 + PENDING 行落库。
     *
     * <p>永不向调用方抛出（含落库失败——catch 全吞后 error 告警返回），保证毒丸隔离路径
     * 不因留痕写入失败而中断消费循环（javadoc 声明优先级：隔离优先于留痕）。
     *
     * @param queueName 来源订阅队列名（溯源消费链路），非空；来源：消费者配置的队列清单
     * @param rawText   帧原文（UTF-8 解码后），非空；仅用于摘要计算与截断留痕，
     *                  调用方须已脱敏或保证无敏感明文（禁入日志）
     * @param stage     失败阶段字面量（PARSE/VALIDATE/PERSIST，ConsumeErrorStage 值域），非空；
     *                  非法值抛 IllegalArgumentException（调用方编程错误，应修正传参）
     * @param errorMsg  失败原因摘要（不带原文敏感值），可空；超 500 字符截断（列宽防线）
     */
    void recordParseFailure(String queueName, String rawText, String stage, String errorMsg);

    /**
     * 消费错误分页查询（管理端"可查可重放"主路径）：队列名/处置状态过滤，id 倒序稳定输出。
     *
     * @param request 分页查询请求，非空
     * @return 分页出参，非空
     */
    PageResult<ConsumeErrorVO> page(ConsumeErrorQueryRequest request);

    /**
     * 重放（重新入解析管道）：CAS 认领（PENDING/REPLAYED → REPLAYED，replay_count 累加）后按
     * sealed 五形态分派回既有消费链（遥测帧入批量入库、状态帧即时处理、告警帧透传评估、命令
     * 结果回推终态）。重放处理失败不回滚认领（记录已如实标记 REPLAYED 供追溯），异常翻译上抛。
     *
     * @param errorId 错误行 ID，非空；来源：管理端点路径变量
     * @return 重放后的错误日志视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1020（404；记录不存在）、
     *                                                  IOT-1021（409；已放弃禁重放/并发 CAS 落败/
     *                                                  载荷缺失或重放处理失败——后两者借承申报，
     *                                                  消息显式区分场景）
     */
    ConsumeErrorVO replay(Long errorId);

    /**
     * 放弃（人工确认不重放，终态）：CAS 仅 PENDING 行迁移 ABANDONED（并发双弃/已处置零行拒绝），
     * 原因追加承载于 error_msg（列宽 500 截断）并留痕处置人与时刻。
     *
     * @param errorId 错误行 ID，非空；来源：管理端点路径变量
     * @param request 放弃请求（原因强制），非空
     * @return 放弃后的错误日志视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1020（404；记录不存在）、
     *                                                  IOT-1021（409；仅 PENDING 可放弃——已重放/
     *                                                  已放弃/并发 CAS 落败；400 借承空白原因）
     */
    ConsumeErrorVO abandon(Long errorId, AbandonConsumeErrorRequest request);
}
