package com.fuyun.integration.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.integration.entity.EventRegistry;

/**
 * 事件契约台账服务：event_registry 表的业务写入口（M20"新增/变更事件类型须先登记"治理约定）。
 *
 * <p>本接口为模块内服务（对外契约出口是 api/ 包的 {@code MessagingGovernance}），登记职责
 * 随治理构件与种子迁移在本模块内闭环。写语义两条红线：登记幂等不覆盖已冻结契约；
 * 订阅只对已登记且未废止的事件放行。
 */
public interface IEventRegistryService extends IService<EventRegistry> {

    /**
     * 登记事件契约（幂等）：同 event_type 已存在时 warn 跳过，禁止静默覆盖已冻结契约。
     *
     * @param spec 登记参数对象，非空；来源：发布方模块或种子迁移
     */
    void register(EventRegistrationSpec spec);

    /**
     * 登记订阅模块（追加式幂等）：事件缺失或已废止时抛异常阻断（事件先登记后订阅）；
     * 订阅模块已在清单中时幂等跳过。
     *
     * @param eventType      事件类型，须已登记且 status=ACTIVE；来源：声明构件（declareConsumerQueue）
     * @param consumerModule 消费者模块域标识，非空；来源：订阅方模块装配代码
     * @throws IllegalStateException 事件未登记或已废止（DEPRECATED）时触发；建议处理策略：
     *                               发布方先登记事件契约，订阅方不得绕过台账订阅
     */
    void registerSubscriber(String eventType, String consumerModule);

    /**
     * 判定事件类型是否已在台账登记。
     *
     * @param eventType 事件类型，非空
     * @return true=已登记（任意状态）；false=未登记
     */
    boolean isRegistered(String eventType);
}
