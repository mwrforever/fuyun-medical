package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.EventRegistryQuery;
import com.fuyun.integration.entity.EventRegistry;
import com.fuyun.integration.mapper.EventRegistryMapper;
import com.fuyun.integration.service.EventRegistrationSpec;
import com.fuyun.integration.service.IEventRegistryService;
import com.fuyun.integration.vo.EventRegistryVO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件契约台账服务实现：event_registry 表的唯一业务写入口（M20 治理约定的执行点）。
 *
 * <p>写语义：登记幂等（同 event_type 已存在时 warn 跳过，不覆盖已冻结契约）；订阅登记追加式
 * （重复追加幂等跳过），事件缺失或 DEPRECATED 时抛 IllegalStateException 阻断订阅方启动；
 * broadcast 标记行拒订（零订阅广播不承载订阅清单，W-6②）；并发追加以「旧清单」为 CAS 条件
 * 单语句条件更新，自旋重试上界 3 次、耗尽 fail-fast（禁静默丢订阅，多实例安全零新增锁）。
 * 单表操作走 ServiceImpl 内置 lambda 链式（宪法 A.4.3-13），select 精确投影（A.4.3-14），
 * 写操作方法级事务最小边界（A.4.2-7）。
 *
 * <p>时间戳列（registered_at/created_at/updated_at）由数据库 DEFAULT now() 与触发器维护，
 * 应用层不写；created_by/updated_by 治理台账取数据库默认 'system'。
 */
@Slf4j
public class EventRegistryServiceImpl extends ServiceImpl<EventRegistryMapper, EventRegistry>
        implements IEventRegistryService {

    /** 订阅清单分隔符：subscriber_modules 列以逗号分隔存储多个模块标识 */
    private static final String SUBSCRIBER_SEPARATOR = ",";

    /** 订阅清单 CAS 最大尝试次数：并发追加竞争窗口内的自旋上界（超限 fail-fast） */
    private static final int SUBSCRIBER_CAS_MAX_ATTEMPTS = 3;

    private final IntegrationConverter converter;

    /**
     * 全参构造器（装配归 MessagingGovernanceConfig @Import）。
     *
     * @param converter 治理域转换器，非空；来源：IntegrationWebConfig @Bean
     */
    public EventRegistryServiceImpl(IntegrationConverter converter) {
        this.converter = converter;
    }

    /**
     * 登记事件契约（幂等，方法级写事务）：查无既有行则插入 status=ACTIVE 契约行；同
     * event_type 已存在（任意状态，含 DEPRECATED）warn 跳过不覆盖——契约一经冻结禁止
     * 静默改写，重复登记只提示。
     *
     * <p>边界条件：①并发首登记 check-then-insert 竞态由 uk_event_registry_event_type 兜底，
     * 后到者命中 DuplicateKeyException 与前置查询同语义幂等跳过（唯一索引为最终保证）；
     * ②broadcast 标记行以 subscriber_modules=broadcast 落库（零订阅广播，W-6②），非广播
     * 事件空清单记空串（待订阅）；③时间戳与操作人列由数据库默认值维护，应用层不写。
     *
     * @param spec 登记参数对象（eventType/producerModule/payloadDesc/broadcast/subscriberModules），
     *             非空；来源：发布方模块装配代码或种子迁移
     */
    @Override
    @Transactional
    public void register(EventRegistrationSpec spec) {
        EventRegistry existing = this.lambdaQuery()
                .eq(EventRegistry::getEventType, spec.eventType())
                .select(EventRegistry::getId, EventRegistry::getStatus)
                .one();
        // 幂等登记：契约冻结后禁止静默改写，重复登记只提示不覆盖
        if (existing != null) {
            log.warn("事件类型 {} 已在 event_registry 登记（status={}），幂等跳过，不覆盖既有契约", spec.eventType(), existing.getStatus());
            return;
        }
        EventRegistry entity = new EventRegistry();
        entity.setEventType(spec.eventType());
        entity.setProducerModule(spec.producerModule());
        entity.setPayloadDesc(spec.payloadDesc());
        // 广播事件以 broadcast 标记落库（零订阅广播，R6-13）；非广播事件空清单记空串（待订阅）
        entity.setSubscriberModules(
                spec.broadcast()
                        ? MessagingConstants.SUBSCRIBER_BROADCAST
                        : (spec.subscriberModules() == null ? "" : spec.subscriberModules()));
        entity.setStatus(MessagingConstants.REGISTRY_STATUS_ACTIVE);
        // 并发首登记兜底：check-then-insert 竞态下后到者命中 uk_event_registry_event_type，
        // 与前置查询幂等语义对齐（warn 跳过不覆盖），唯一索引为最终保证（backend 宪法 A.5-6 同型语义）
        try {
            this.save(entity);
        } catch (DuplicateKeyException e) {
            log.warn("事件类型 {} 并发登记命中唯一索引，先行者已登记，幂等跳过，不覆盖既有契约", spec.eventType());
            return;
        }
        log.info(
                "事件登记完成：event_type={}，producer={}，broadcast={}，subscriber_modules={}",
                spec.eventType(),
                spec.producerModule(),
                spec.broadcast(),
                entity.getSubscriberModules());
    }

    /**
     * 登记订阅模块（追加式幂等，方法级写事务；declareConsumerQueue 的声明副作用，启动期
     * 调用）：读契约行（精确投影）→ 校验已登记且未废止 → 逗号清单追加订阅模块 → 以「读取的
     * 旧清单」为 CAS 条件单语句条件更新。
     *
     * <p>边界条件与并发：事件未登记/已废止抛 IllegalStateException 阻断订阅方启动（先登记
     * 后订阅）；broadcast 标记行拒订（零订阅广播不承载订阅清单，W-6②）；模块已在清单时
     * 幂等跳过（防声明重放产生冗余写）；并发追加后到者 CAS 影响 0 行（丢更新防线）自旋
     * 重读重算，超过 3 次上界 fail-fast 拒绝静默丢订阅——多实例安全且零新增锁。
     *
     * @param eventType      事件类型，非空；须已登记且 status=ACTIVE；来源：声明构件装配链路
     * @param consumerModule 消费者模块域标识，非空；来源：订阅方模块装配代码
     * @throws IllegalStateException 事件未登记或已废止、broadcast 行拒订、并发竞争超自旋上界时
     *                               触发；建议处理策略：发布方先登记契约 / 修正契约行 /
     *                               重启装配进程重试
     */
    @Override
    @Transactional
    public void registerSubscriber(String eventType, String consumerModule) {
        for (int attempt = 1; attempt <= SUBSCRIBER_CAS_MAX_ATTEMPTS; attempt++) {
            EventRegistry registry = this.lambdaQuery()
                    .eq(EventRegistry::getEventType, eventType)
                    .select(EventRegistry::getId, EventRegistry::getStatus, EventRegistry::getSubscriberModules)
                    .one();
            // 事件先登记后订阅：未登记事件拒绝订阅，阻断消费队列声明（M20 治理约定）
            if (registry == null) {
                throw new IllegalStateException("事件类型 " + eventType + " 未在 event_registry 登记，禁止订阅（事件先登记后订阅）");
            }
            if (MessagingConstants.REGISTRY_STATUS_DEPRECATED.equals(registry.getStatus())) {
                throw new IllegalStateException("事件类型 " + eventType + " 已废止（DEPRECATED），禁止订阅");
            }
            String currentModules = registry.getSubscriberModules() == null ? "" : registry.getSubscriberModules();
            // broadcast 拒订守卫（W-6②）：零订阅广播标记行不承载订阅清单，追加会破坏 R6-13 语义
            if (MessagingConstants.SUBSCRIBER_BROADCAST.equals(currentModules.trim())) {
                throw new IllegalStateException("事件类型 " + eventType
                        + " 为零订阅广播标记行（subscriber_modules=broadcast），不承载订阅清单——"
                        + "如需订阅制治理，请由发布方先修正契约行后再订阅");
            }
            List<String> modules = new ArrayList<>();
            if (!currentModules.isBlank()) {
                modules.addAll(Arrays.asList(currentModules.split(SUBSCRIBER_SEPARATOR)));
            }
            // 重复订阅幂等：清单中已存在该模块时跳过，避免声明重放产生冗余写
            if (modules.contains(consumerModule)) {
                log.info("订阅模块 {} 已在事件 {} 的订阅清单中，幂等跳过", consumerModule, eventType);
                return;
            }
            modules.add(consumerModule);
            String merged = String.join(SUBSCRIBER_SEPARATOR, modules);
            // 并发守卫（W-6②）：条件更新以「读取到的旧清单」为 CAS 条件——多实例并发追加时
            // 后到者影响 0 行（丢更新防线），自旋重读重算；上界耗尽即 fail-fast 禁静默丢订阅
            boolean updated = this.lambdaUpdate()
                    .eq(EventRegistry::getId, registry.getId())
                    .eq(EventRegistry::getSubscriberModules, currentModules)
                    .set(EventRegistry::getSubscriberModules, merged)
                    .update();
            if (updated) {
                log.info("订阅登记完成：event_type={}，新增订阅模块={}，subscriber_modules={}", eventType, consumerModule, merged);
                return;
            }
            log.warn("订阅清单并发变更，重读重算重试：event_type={}，consumer_module={}，attempt={}", eventType, consumerModule, attempt);
        }
        throw new IllegalStateException(
                "事件类型 " + eventType + " 订阅登记并发竞争超过 " + SUBSCRIBER_CAS_MAX_ATTEMPTS + " 次重试，拒绝静默丢订阅（请重启装配进程重试）");
    }

    /**
     * 判定事件类型是否已在台账登记（治理校验查询，只读事务，精确投影 id 列）。
     *
     * @param eventType 事件类型，非空；来源：发布/订阅装配代码的治理校验
     * @return true=已登记；false=未登记。注意：已登记含 DEPRECATED 已废止状态——判可订阅
     *         须另行校验状态（registerSubscriber 才是订阅放行的完整校验入口）
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isRegistered(String eventType) {
        return this.lambdaQuery()
                .eq(EventRegistry::getEventType, eventType)
                .select(EventRegistry::getId)
                .exists();
    }

    /**
     * 分页查询事件契约台账（管理面只读，只读事务）：按事件类型/生产方/状态等值过滤，
     * 事件类型名升序 + 主键兜底排序（深翻页防漏行）；契约 0 基页码与 MP 分页器 1 基在
     * 服务层唯一转换点互转。
     *
     * @param query 查询条件，非空；page 0 基、size 1-200；来源：契约台账端点参数对象
     * @return 分页出参（0 基页码），非空；无匹配时 content 为空清单
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<EventRegistryVO> query(EventRegistryQuery query) {
        LambdaQueryWrapper<EventRegistry> wrapper = Wrappers.lambdaQuery(EventRegistry.class)
                .eq(query.eventType() != null, EventRegistry::getEventType, query.eventType())
                .eq(query.producerModule() != null, EventRegistry::getProducerModule, query.producerModule())
                .eq(query.status() != null, EventRegistry::getStatus, query.status())
                // 排序唯一性约束（A.4.3-17）：类型名 + 主键
                .orderByAsc(EventRegistry::getEventType)
                .orderByAsc(EventRegistry::getId);
        Page<EventRegistry> page = this.page(new Page<>(query.page() + 1L, query.size()), wrapper);
        return PageResult.of(
                converter.toEventRegistryVOs(page.getRecords()),
                page.getCurrent() - 1,
                page.getSize(),
                page.getTotal());
    }
}
