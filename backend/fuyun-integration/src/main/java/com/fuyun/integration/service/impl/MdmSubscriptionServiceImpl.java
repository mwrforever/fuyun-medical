package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.api.IntegrationErrorCode;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.MdmSubscriptionCreateRequest;
import com.fuyun.integration.dto.MdmSubscriptionQuery;
import com.fuyun.integration.entity.MdmSubscription;
import com.fuyun.integration.mapper.MdmSubscriptionMapper;
import com.fuyun.integration.service.IMdmSubscriptionService;
import com.fuyun.integration.vo.MdmSubscriptionVO;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主数据订阅服务实现：mdm_subscription 的唯一业务写入口（FU-M20-04 分发关系台账）。
 *
 * <p>写语义：登记幂等（同主题同订阅方已存在时 warn 跳过，不覆盖既有对账进度）；并发首登记由
 * uk_mdm_subscription_topic_subscriber 兜底（冲突后回读既有行返回，与 EventRegistryServiceImpl
 * 同款幂等姿态）；注销走 @TableLogic 逻辑删。对账类列（last_* / recon_status）本 PR 只写登记初值。
 *
 * <p>归 service/impl 包 = JaCoCo 核心包 PACKAGE LINE 1.00 覆盖对象。
 */
@Slf4j
public class MdmSubscriptionServiceImpl extends ServiceImpl<MdmSubscriptionMapper, MdmSubscription>
        implements IMdmSubscriptionService {

    private final IntegrationConverter converter;

    /**
     * 全参构造器（装配归 IntegrationMdmConfig @Import）。
     *
     * @param converter 治理域转换器，非空；来源：IntegrationWebConfig @Bean
     */
    public MdmSubscriptionServiceImpl(IntegrationConverter converter) {
        this.converter = converter;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<MdmSubscriptionVO> query(MdmSubscriptionQuery query) {
        LambdaQueryWrapper<MdmSubscription> wrapper = Wrappers.lambdaQuery(MdmSubscription.class)
                .eq(query.topic() != null, MdmSubscription::getTopic, query.topic())
                .eq(query.subscriberModule() != null, MdmSubscription::getSubscriberModule, query.subscriberModule())
                // 排序唯一性约束（A.4.3-17）：主题 + 主键（矩阵阅读顺序）
                .orderByAsc(MdmSubscription::getTopic)
                .orderByAsc(MdmSubscription::getId);
        Page<MdmSubscription> page = this.page(new Page<>(query.page() + 1L, query.size()), wrapper);
        return PageResult.of(
                converter.toMdmSubscriptionVOs(page.getRecords()),
                page.getCurrent() - 1,
                page.getSize(),
                page.getTotal());
    }

    @Override
    @Transactional
    public MdmSubscriptionVO register(MdmSubscriptionCreateRequest request) {
        if (!MdmConstants.TOPICS.contains(request.topic())) {
            throw new BizException(
                    IntegrationErrorCode.MDM_TOPIC_UNKNOWN,
                    HttpStatus.BAD_REQUEST,
                    "未知主数据主题：" + request.topic() + "（合法取值 " + MdmConstants.TOPICS + "）");
        }
        MdmSubscription existing = this.lambdaQuery()
                .eq(MdmSubscription::getTopic, request.topic())
                .eq(MdmSubscription::getSubscriberModule, request.subscriberModule())
                .one();
        // 幂等登记：已登记的订阅关系不覆盖（对账进度属既有事实，静默改写会让对账结论失真）
        if (existing != null) {
            log.warn("主数据订阅关系已存在，幂等跳过不覆盖：topic={}，subscriber_module={}", request.topic(), request.subscriberModule());
            return converter.toMdmSubscriptionVO(existing);
        }
        MdmSubscription entity = new MdmSubscription();
        entity.setTopic(request.topic());
        entity.setSubscriberModule(request.subscriberModule());
        entity.setSyncMode(request.syncMode());
        // 对账状态登记初值 PENDING（待对账）；对账任务的其余取值随 M01 版本化回源接口引入
        entity.setReconStatus(MdmConstants.RECON_STATUS_PENDING);
        try {
            this.save(entity);
        } catch (DuplicateKeyException e) {
            // 并发首登记竞态：唯一索引为最终保证，冲突后回读既有行返回（幂等语义与前置查询一致）
            log.warn(
                    "主数据订阅并发登记命中唯一索引，幂等跳过不覆盖：topic={}，subscriber_module={}",
                    request.topic(),
                    request.subscriberModule());
            MdmSubscription row = this.lambdaQuery()
                    .eq(MdmSubscription::getTopic, request.topic())
                    .eq(MdmSubscription::getSubscriberModule, request.subscriberModule())
                    .one();
            return converter.toMdmSubscriptionVO(row);
        }
        log.info(
                "主数据订阅登记完成：topic={}，subscriber_module={}，sync_mode={}",
                request.topic(),
                request.subscriberModule(),
                request.syncMode());
        return converter.toMdmSubscriptionVO(entity);
    }

    @Override
    @Transactional
    public void unregister(Long id) {
        if (!this.removeById(id)) {
            throw new BizException(
                    IntegrationErrorCode.MDM_SUBSCRIPTION_NOT_FOUND, HttpStatus.NOT_FOUND, "主数据订阅不存在：id=" + id);
        }
        log.info("主数据订阅注销完成（逻辑删）：id={}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listSubscriberModules(String topic) {
        return this.list(Wrappers.lambdaQuery(MdmSubscription.class)
                        .eq(MdmSubscription::getTopic, topic)
                        .select(MdmSubscription::getSubscriberModule)
                        .orderByAsc(MdmSubscription::getId))
                .stream()
                .map(MdmSubscription::getSubscriberModule)
                .toList();
    }
}
