package com.fuyun.integration.api;

import org.springframework.amqp.core.Declarables;

/**
 * 消息治理构件统一 API：全系统交换机/队列声明的唯一合法入口（M20 §7 治理约定）。
 *
 * <p>治理目标：交换机全集固定 fy.topic / fy.dlx / fy.delay 三件套（任何模块禁止私建，A.5-4）；
 * 业务队列全部 quorum 类型且经本构件声明（RabbitAdmin 幂等声明）；事件先登记后订阅——
 * 事件未登记或已废止时声明失败阻断启动，落实"新增/变更事件类型须先在 event_registry 登记"。
 *
 * <p>标准用法（各模块装配代码）：返回的 Declarables 以 {@code @Bean} 暴露，由 RabbitAdmin
 * 幂等声明，测试与生产一致：
 * <pre>{@code
 * @Bean Declarables dictConsumerQueue(MessagingGovernance governance) {
 *     return governance.declareConsumerQueue(new ConsumerQueueSpec("system", "system.dict.published"));
 * }
 * }</pre>
 */
public interface MessagingGovernance {

    /**
     * 声明消费队列并绑定主交换机：队列 {@code q.<consumerModule>.<eventType>}（durable、quorum、
     * 死信指向 fy.dlx 且不设死信路由键以保留原始路由键）+ Binding 到 fy.topic（key=eventType）；
     * 同时做订阅自动登记（registerSubscriber）。
     *
     * @param spec 消费队列声明契约，非空；来源：订阅方模块装配代码
     * @return 声明集合（队列 + 绑定）；由调用方以 @Bean 暴露交 RabbitAdmin 幂等声明
     * @throws IllegalArgumentException 命名审查失败（eventType 非小写点分 ≥3 段、consumerModule
     *                                  非空小写）时触发；建议处理策略：修正装配代码命名
     * @throws IllegalStateException    事件未登记或已废止时触发（登记服务抛出）；建议处理策略：
     *                                  发布方先在 event_registry 登记事件契约后再订阅
     */
    Declarables declareConsumerQueue(ConsumerQueueSpec spec);

    /**
     * 声明延迟档位队列并绑定延迟交换机：队列 {@code delay.<business>}（durable、quorum、
     * x-message-ttl=档位时长、死信转发 fy.topic 且路由键=targetRoutingKey）+ Binding 到
     * fy.delay（key=队列名）。一条队列一个档位，P0 不声明任何业务延迟队列。
     *
     * @param spec 延迟队列声明契约，非空；来源：声明方模块装配代码
     * @return 声明集合（队列 + 绑定）；由调用方以 @Bean 暴露交 RabbitAdmin 幂等声明
     * @throws IllegalArgumentException 命名审查失败、ttl ≤ 0 或超出 int 毫秒上界、目标路由键空白时触发；
     *                                  建议处理策略：修正装配代码参数
     */
    Declarables declareDelayQueue(DelayQueueSpec spec);
}
