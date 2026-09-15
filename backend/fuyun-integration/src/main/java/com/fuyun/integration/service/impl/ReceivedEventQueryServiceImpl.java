package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.ReceivedEventQuery;
import com.fuyun.integration.entity.ReceivedEvent;
import com.fuyun.integration.mapper.ReceivedEventMapper;
import com.fuyun.integration.service.IReceivedEventQueryService;
import com.fuyun.integration.vo.ReceivedEventVO;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消费台账查询实现：received_event 的只读分页查询（M20 FU-M20-06 事件查询台）。
 *
 * <p>禁写：本类不提供任何写方法——写路径唯一入口是两层幂等构件（MessageIdempotencyServiceImpl），
 * 避免同一台账出现两条写入语义（读-改-写与幂等登记冲突）。
 *
 * <p>归 service/impl 包 = JaCoCo 核心包 PACKAGE LINE 1.00 覆盖对象。
 */
public class ReceivedEventQueryServiceImpl implements IReceivedEventQueryService {

    private final ReceivedEventMapper receivedEventMapper;

    private final IntegrationConverter converter;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param receivedEventMapper 消费台账 mapper，非空；来源：@MapperScan 扫描注册
     * @param converter           治理域转换器，非空；来源：IntegrationWebConfig @Bean
     */
    public ReceivedEventQueryServiceImpl(ReceivedEventMapper receivedEventMapper, IntegrationConverter converter) {
        this.receivedEventMapper = receivedEventMapper;
        this.converter = converter;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ReceivedEventVO> query(ReceivedEventQuery query) {
        LambdaQueryWrapper<ReceivedEvent> wrapper = Wrappers.lambdaQuery(ReceivedEvent.class)
                .eq(query.eventType() != null, ReceivedEvent::getEventType, query.eventType())
                .eq(query.eventId() != null, ReceivedEvent::getEventId, query.eventId())
                .eq(query.consumerModule() != null, ReceivedEvent::getConsumerModule, query.consumerModule())
                .eq(query.status() != null, ReceivedEvent::getStatus, query.status())
                .ge(query.receivedFrom() != null, ReceivedEvent::getReceivedAt, query.receivedFrom())
                .le(query.receivedTo() != null, ReceivedEvent::getReceivedAt, query.receivedTo())
                // 排序唯一性约束（A.4.3-17）：接收时间相同时以主键兜底
                .orderByDesc(ReceivedEvent::getReceivedAt)
                .orderByDesc(ReceivedEvent::getId);
        Page<ReceivedEvent> page = receivedEventMapper.selectPage(new Page<>(query.page() + 1L, query.size()), wrapper);
        return PageResult.of(
                converter.toReceivedEventVOs(page.getRecords()),
                page.getCurrent() - 1,
                page.getSize(),
                page.getTotal());
    }
}
