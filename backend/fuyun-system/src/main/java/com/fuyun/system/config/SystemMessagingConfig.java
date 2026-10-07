package com.fuyun.system.config;

import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.system.constants.SystemMessagingConstants;
import com.fuyun.system.internal.DictPublishedListener;
import com.fuyun.system.internal.PermissionRegistryReloadListener;
import com.fuyun.system.internal.SystemEventPublisher;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 系统模块消息装配（B3.2，BRIEF-PR3-01 §3.2）：字典广播的发布器/消费者 Bean 注册与
 * 消费队列治理声明集中点；PR-4F 追加权限矩阵变更生效链路（PermissionRegistryReloadListener
 * 双消费入口：治理命名队列 + 匿名广播队列，W-96②）。
 *
 * <p>com.fuyun.system 包不在 @SpringBootApplication 扫描范围（com.fuyun.app.*）内，本配置经
 * fuyun-app SystemConfig @Import 生效（不放宽扫描）。队列声明走 MessagingGovernance 构件
 * （先登记后订阅：事件已在 V5 种子登记，订阅方 system 经声明副作用自动补登记 subscriber_modules）；
 * 交换机全集仍由 integration MessagingGovernanceConfig 声明，本配置不重复（禁私建交换机 A.5-4）。
 */
@Configuration
@Import({DictPublishedListener.class, SystemEventPublisher.class, PermissionRegistryReloadListener.class})
public class SystemMessagingConfig {

    /**
     * 声明 system 模块的字典发布消费队列并绑定 fy.topic（事件未登记时构件抛异常阻断启动）。
     *
     * @param governance 消息治理构件，非空；来源：integration MessagingGovernanceConfig 装配
     * @return 声明集合（quorum 队列 + 绑定）；由 RabbitAdmin 随连接建立幂等声明
     */
    @Bean
    public Declarables dictPublishedConsumerQueue(MessagingGovernance governance) {
        return governance.declareConsumerQueue(
                new ConsumerQueueSpec(SystemMessagingConstants.MODULE, SystemMessagingConstants.EVENT_DICT_PUBLISHED));
    }

    /**
     * 声明 system 模块的权限矩阵变更消费队列并绑定 fy.topic（PR-4F W-96②）。
     *
     * <p>「先登记后订阅」校验前提：V1120 已种 event_registry id 84（system.permission.changed），
     * 启动顺序 = Flyway 迁移先于 RabbitAdmin 声明（Boot 既有顺序成立）。广播入口的匿名排他队列
     * 随实例生灭不经本声明（设计注记见 PermissionRegistryReloadListener#onPermissionMatrixBroadcast）。
     *
     * @param governance 消息治理构件，非空；来源：integration MessagingGovernanceConfig 装配
     * @return 声明集合（quorum 队列 + 绑定）；由 RabbitAdmin 随连接建立幂等声明
     */
    @Bean
    public Declarables permissionChangedConsumerQueue(MessagingGovernance governance) {
        return governance.declareConsumerQueue(new ConsumerQueueSpec(
                SystemMessagingConstants.MODULE, SystemMessagingConstants.EVENT_PERMISSION_CHANGED));
    }
}
