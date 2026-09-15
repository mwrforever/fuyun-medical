package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.utils.TextTruncate;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.api.IntegrationErrorCode;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.DeadLetterCloseRequest;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.mapper.DeadLetterMapper;
import com.fuyun.integration.service.IDeadLetterService;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 死信管理服务实现：查询与处置的单一写入口（M20 §5 状态机的执行点）。
 *
 * <p>事务边界（A.4.2-7）：查询方法只读事务；处置动作（重放/关闭）的事务边界与 MQ 投递分离——
 * 投递在事务外先执行，随后以单语句 CAS UPDATE 落状态（含状态与上限守卫），多实例并发下
 * 后到者影响 0 行，无丢更新与双处置。
 *
 * <p>归 service/impl 包 = JaCoCo 核心包 PACKAGE LINE 1.00 覆盖对象（backend/pom.xml 核心包名单）。
 */
@Slf4j
public class DeadLetterServiceImpl extends ServiceImpl<DeadLetterMapper, DeadLetter> implements IDeadLetterService {

    /** 无操作人上下文（非 HTTP 线程）时的处理人兜底值：与 created_by 系统操作口径一致 */
    private static final String DEFAULT_HANDLER = "system";

    private final IntegrationConverter converter;

    private final RabbitTemplate rabbitTemplate;

    private final AmqpAdmin amqpAdmin;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param converter      治理域转换器，非空；来源：IntegrationWebConfig @Bean
     * @param rabbitTemplate MQ 发送模板，非空；来源：Boot 自动装配（correlated confirm + mandatory）
     * @param amqpAdmin      AMQP 管理台，非空；来源：Boot 自动装配（来源队列在位校验）
     */
    public DeadLetterServiceImpl(IntegrationConverter converter, RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin) {
        this.converter = converter;
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<DeadLetterVO> query(DeadLetterQuery query) {
        LambdaQueryWrapper<DeadLetter> wrapper = Wrappers.lambdaQuery(DeadLetter.class)
                .eq(query.status() != null, DeadLetter::getStatus, query.status())
                .eq(query.eventType() != null, DeadLetter::getEventType, query.eventType())
                .eq(query.eventId() != null, DeadLetter::getEventId, query.eventId())
                .eq(query.sourceQueue() != null, DeadLetter::getSourceQueue, query.sourceQueue())
                // 排序唯一性约束（A.4.3-17）：时间相同时以主键兜底，防深翻页漏行
                .orderByDesc(DeadLetter::getFirstDeadAt)
                .orderByDesc(DeadLetter::getId);
        // 契约 0 基（A.3-6）↔ MP 分页器 1 基：服务层唯一转换点，进出各一次
        Page<DeadLetter> page = this.page(new Page<>(query.page() + 1L, query.size()), wrapper);
        return PageResult.of(
                converter.toDeadLetterVOs(page.getRecords()), page.getCurrent() - 1, page.getSize(), page.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public DeadLetterDetailVO detail(Long id) {
        DeadLetter row = this.getById(id);
        if (row == null) {
            throw new BizException(IntegrationErrorCode.DEAD_LETTER_NOT_FOUND, HttpStatus.NOT_FOUND, "死信不存在：id=" + id);
        }
        return converter.toDeadLetterDetailVO(row);
    }

    @Override
    public DeadLetterDetailVO replay(Long id) {
        DeadLetter row = requirePending(id, "重放");
        // 重推上限（Spec 无值，控制器拍板）：达到上限拒绝，交运维关闭处置
        if (row.getReplayCount() != null && row.getReplayCount() >= MessagingConstants.DEAD_LETTER_REPLAY_MAX_COUNT) {
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_REPLAY_LIMIT_EXCEEDED,
                    HttpStatus.CONFLICT,
                    "死信重推次数已达上限 " + MessagingConstants.DEAD_LETTER_REPLAY_MAX_COUNT + "：id=" + id);
        }
        String routingKey = resolveRoutingKey(row);
        // 不可路由防线：来源队列不在位时拒绝（mandatory 退回回调为异步，同步响应内无法感知不可路由）
        if (amqpAdmin.getQueueProperties(row.getSourceQueue()) == null) {
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_NOT_REPLAYABLE,
                    HttpStatus.CONFLICT,
                    "来源队列不在位，禁止重放（防不可路由静默丢失）：source_queue=" + row.getSourceQueue());
        }
        // 事务外投递（A.4.2-7 事务内禁消息发送）：投递先行，状态写回随后（单语句 CAS，无需方法级事务）
        if (!publishOriginalFrame(row, routingKey)) {
            // 投递失败：回到待处理并累加重放次数（Spec §5「重放失败回到待处理并累加重放次数」）
            markReplayAttempt(id, MessagingConstants.DEAD_LETTER_STATUS_PENDING);
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_REPLAY_DELIVERY_FAILED,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "死信重放投递失败，已回到待处理并累加重放次数：id=" + id);
        }
        if (!markReplayAttempt(id, MessagingConstants.DEAD_LETTER_STATUS_REPLAYED)) {
            // CAS 影响 0 行：并发处置已抢先（本次投递已发出，消费侧幂等防重复业务）
            log.warn("死信重放状态写回未命中（并发处置抢先，本次投递已发出）：id={}", id);
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE,
                    HttpStatus.CONFLICT,
                    "死信状态已变化（并发处置），请刷新后重试：id=" + id);
        }
        log.info(
                "死信重放完成：id={}，event_id={}，routing_key={}，replay_count={}",
                id,
                row.getEventId(),
                routingKey,
                row.getReplayCount() == null ? 1 : row.getReplayCount() + 1);
        return detail(id);
    }

    @Override
    public DeadLetterDetailVO close(Long id, DeadLetterCloseRequest request) {
        requirePending(id, "关闭");
        String handler = resolveHandler();
        // 单语句 CAS（状态守卫）：并发处置后到者影响 0 行，天然幂等于先行者结果
        boolean closed = this.lambdaUpdate()
                .eq(DeadLetter::getId, id)
                .eq(DeadLetter::getStatus, MessagingConstants.DEAD_LETTER_STATUS_PENDING)
                .set(DeadLetter::getStatus, MessagingConstants.DEAD_LETTER_STATUS_CLOSED)
                .set(DeadLetter::getHandleNote, request.handleNote())
                .set(DeadLetter::getHandler, handler)
                .set(DeadLetter::getHandledAt, OffsetDateTime.now())
                .update();
        if (!closed) {
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE,
                    HttpStatus.CONFLICT,
                    "死信状态已变化（并发处置），请刷新后重试：id=" + id);
        }
        // 关闭原因属运维备注（可能含院内业务描述）：日志只记长度不记原文（敏感信息禁入日志）
        log.info(
                "死信关闭完成：id={}，handler={}，原因长度={}",
                id,
                handler,
                request.handleNote().length());
        return detail(id);
    }

    /**
     * 载入死信并校验状态可处置：仅 PENDING（Spec §5 状态机——REPLAYED 已处置、CLOSED 为终态）。
     *
     * @param id     死信 ID，非空
     * @param action 动作中文名（进异常文案）
     * @return 死信行，非空
     * @throws BizException id 不存在（INT-1001）或状态非 PENDING（INT-1002）时触发
     */
    private DeadLetter requirePending(Long id, String action) {
        DeadLetter row = this.getById(id);
        if (row == null) {
            throw new BizException(IntegrationErrorCode.DEAD_LETTER_NOT_FOUND, HttpStatus.NOT_FOUND, "死信不存在：id=" + id);
        }
        if (!MessagingConstants.DEAD_LETTER_STATUS_PENDING.equals(row.getStatus())) {
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE,
                    HttpStatus.CONFLICT,
                    "死信当前状态不允许" + action + "（仅 PENDING 可处置）：id=" + id + "，status=" + row.getStatus());
        }
        return row;
    }

    /**
     * 解析重放路由键：优先原始路由键（死信转发保留原路由键），轨迹缺失时回退事件类型；
     * 两者皆空（信封不合规帧）拒绝重放。
     *
     * @param row 死信行，非空
     * @return 重放路由键，非空
     * @throws BizException 无可用路由键（INT-1004）时触发
     */
    private String resolveRoutingKey(DeadLetter row) {
        String routingKey =
                row.getRoutingKey() == null || row.getRoutingKey().isBlank() ? row.getEventType() : row.getRoutingKey();
        if (routingKey == null || routingKey.isBlank()) {
            throw new BizException(
                    IntegrationErrorCode.DEAD_LETTER_NOT_REPLAYABLE,
                    HttpStatus.CONFLICT,
                    "死信无可用路由键（信封不合规帧），禁止重放：id=" + row.getId());
        }
        return routingKey;
    }

    /**
     * 重放投递：以 payload_body 原文重建消息投 fy.topic，不走消息转换器（原文即 CF-1 信封线格式，
     * 经 Jackson 转换器会被二次序列化为 JSON 字符串而破坏线格式）。
     *
     * @param row        死信行，非空；取 payloadBody 原文
     * @param routingKey 重放路由键，非空
     * @return true=已发出；false=投递异常（已记 error 日志，由调用方落 PENDING 语义）
     */
    private boolean publishOriginalFrame(DeadLetter row, String routingKey) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(row.getPayloadBody().getBytes(StandardCharsets.UTF_8), properties);
        try {
            rabbitTemplate.send(MessagingConstants.EXCHANGE_TOPIC, routingKey, message);
            return true;
        } catch (RuntimeException e) {
            log.error("死信重放投递失败：id={}，routing_key={}，原因={}", row.getId(), routingKey, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 单语句 CAS 写回重放尝试（多实例安全，禁应用层读-改-写）：状态与上限双守卫，影响 0 行即并发抢先。
     *
     * <p>留痕口径：成功置 REPLAYED 时写 handler/handled_at（处置留痕）；失败回 PENDING 时只累加
     * replay_count（Spec §5 只要求这两项效果，不污染处理人语义）。
     *
     * @param id     死信 ID，非空
     * @param status 目标状态（REPLAYED 成功 / PENDING 投递失败）
     * @return true=本次 CAS 生效；false=并发处置已抢先或已达上限
     */
    private boolean markReplayAttempt(Long id, String status) {
        boolean replayed = MessagingConstants.DEAD_LETTER_STATUS_REPLAYED.equals(status);
        return this.lambdaUpdate()
                .eq(DeadLetter::getId, id)
                .eq(DeadLetter::getStatus, MessagingConstants.DEAD_LETTER_STATUS_PENDING)
                .lt(DeadLetter::getReplayCount, MessagingConstants.DEAD_LETTER_REPLAY_MAX_COUNT)
                .set(DeadLetter::getStatus, status)
                .setSql("replay_count = replay_count + 1")
                .set(replayed, DeadLetter::getHandler, resolveHandler())
                .set(replayed, DeadLetter::getHandledAt, OffsetDateTime.now())
                .update();
    }

    /**
     * 解析处理人标识：取认证拦截器注入的操作人上下文，非 HTTP 线程回退 system；
     * 按 handler 列宽截断（W-6① 同款列宽防线，防超长标识致整行写入失败）。
     *
     * @return 处理人标识，非空，长度不超过 HANDLER_MAX_LENGTH
     */
    private static String resolveHandler() {
        String operator = OperatorContextHolder.get();
        String handler = operator == null || operator.isBlank() ? DEFAULT_HANDLER : operator;
        return TextTruncate.truncate(handler, MessagingConstants.HANDLER_MAX_LENGTH);
    }
}
