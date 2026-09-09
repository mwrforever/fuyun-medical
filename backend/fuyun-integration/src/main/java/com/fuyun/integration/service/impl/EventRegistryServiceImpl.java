package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.EventRegistry;
import com.fuyun.integration.mapper.EventRegistryMapper;
import com.fuyun.integration.service.EventRegistrationSpec;
import com.fuyun.integration.service.IEventRegistryService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件契约台账服务实现：event_registry 表的唯一业务写入口（M20 治理约定的执行点）。
 *
 * <p>写语义：登记幂等（同 event_type 已存在时 warn 跳过，不覆盖已冻结契约）；订阅登记追加式
 * （重复追加幂等跳过），事件缺失或 DEPRECATED 时抛 IllegalStateException 阻断订阅方启动。
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
        this.save(entity);
        log.info(
                "事件登记完成：event_type={}，producer={}，broadcast={}，subscriber_modules={}",
                spec.eventType(),
                spec.producerModule(),
                spec.broadcast(),
                entity.getSubscriberModules());
    }

    @Override
    @Transactional
    public void registerSubscriber(String eventType, String consumerModule) {
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
        List<String> modules = new ArrayList<>();
        if (registry.getSubscriberModules() != null
                && !registry.getSubscriberModules().isBlank()) {
            modules.addAll(Arrays.asList(registry.getSubscriberModules().split(SUBSCRIBER_SEPARATOR)));
        }
        // 重复订阅幂等：清单中已存在该模块时跳过，避免声明重放产生冗余写
        if (modules.contains(consumerModule)) {
            log.info("订阅模块 {} 已在事件 {} 的订阅清单中，幂等跳过", consumerModule, eventType);
            return;
        }
        modules.add(consumerModule);
        String merged = String.join(SUBSCRIBER_SEPARATOR, modules);
        this.lambdaUpdate()
                .eq(EventRegistry::getId, registry.getId())
                .set(EventRegistry::getSubscriberModules, merged)
                .update();
        log.info("订阅登记完成：event_type={}，新增订阅模块={}，subscriber_modules={}", eventType, consumerModule, merged);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isRegistered(String eventType) {
        return this.lambdaQuery()
                .eq(EventRegistry::getEventType, eventType)
                .select(EventRegistry::getId)
                .exists();
    }
}
