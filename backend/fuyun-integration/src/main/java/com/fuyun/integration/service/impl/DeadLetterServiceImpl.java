package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.api.IntegrationErrorCode;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.mapper.DeadLetterMapper;
import com.fuyun.integration.service.IDeadLetterService;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import lombok.extern.slf4j.Slf4j;
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

    private final IntegrationConverter converter;

    /**
     * 全参构造器（装配归 IntegrationWebConfig @Import）。
     *
     * @param converter 治理域转换器，非空；来源：IntegrationWebConfig @Bean
     */
    public DeadLetterServiceImpl(IntegrationConverter converter) {
        this.converter = converter;
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
}
