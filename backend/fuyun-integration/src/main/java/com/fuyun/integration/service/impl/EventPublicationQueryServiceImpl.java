package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.EventPublicationQuery;
import com.fuyun.integration.entity.EventPublication;
import com.fuyun.integration.mapper.EventPublicationMapper;
import com.fuyun.integration.service.IEventPublicationQueryService;
import com.fuyun.integration.vo.EventPublicationVO;
import org.springframework.transaction.annotation.Transactional;

/**
 * 投递台账查询实现：event_publication 的只读分页查询（PR-1a 可靠投递链路的可观测面）。
 *
 * <p>只读边界：本类不提供任何写方法；表写入与清理归框架与 EventOpsJob。
 * 归 service/impl 包 = JaCoCo 核心包 PACKAGE LINE 1.00 覆盖对象。
 */
public class EventPublicationQueryServiceImpl implements IEventPublicationQueryService {

    private final EventPublicationMapper eventPublicationMapper;

    private final IntegrationConverter converter;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param eventPublicationMapper 投递注册表只读 mapper，非空；来源：@MapperScan 扫描注册
     * @param converter              治理域转换器，非空；来源：IntegrationWebConfig @Bean
     */
    public EventPublicationQueryServiceImpl(
            EventPublicationMapper eventPublicationMapper, IntegrationConverter converter) {
        this.eventPublicationMapper = eventPublicationMapper;
        this.converter = converter;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<EventPublicationVO> query(EventPublicationQuery query) {
        boolean incomplete = MessagingConstants.PUBLICATION_STATUS_INCOMPLETE.equals(query.status());
        boolean completed = MessagingConstants.PUBLICATION_STATUS_COMPLETED.equals(query.status());
        LambdaQueryWrapper<EventPublication> wrapper = Wrappers.lambdaQuery(EventPublication.class)
                .eq(query.eventType() != null, EventPublication::getEventType, query.eventType())
                // 完成态无独立列：以 completion_date 空/非空表达（与 VO 派生口径同源）
                .isNull(incomplete, EventPublication::getCompletionDate)
                .isNotNull(completed, EventPublication::getCompletionDate)
                .ge(query.publishedFrom() != null, EventPublication::getPublicationDate, query.publishedFrom())
                .le(query.publishedTo() != null, EventPublication::getPublicationDate, query.publishedTo())
                // 排序唯一性约束（A.4.3-17）：发布时间相同时以主键兜底
                .orderByDesc(EventPublication::getPublicationDate)
                .orderByDesc(EventPublication::getId);
        Page<EventPublication> page =
                eventPublicationMapper.selectPage(new Page<>(query.page() + 1L, query.size()), wrapper);
        return PageResult.of(
                converter.toEventPublicationVOs(page.getRecords()),
                page.getCurrent() - 1,
                page.getSize(),
                page.getTotal());
    }
}
