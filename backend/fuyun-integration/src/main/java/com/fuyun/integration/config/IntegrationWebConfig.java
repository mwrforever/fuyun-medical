package com.fuyun.integration.config;

import com.fuyun.integration.controller.DeadLetterController;
import com.fuyun.integration.controller.EventPublicationController;
import com.fuyun.integration.controller.ReceivedEventController;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.service.impl.DeadLetterServiceImpl;
import com.fuyun.integration.service.impl.EventPublicationQueryServiceImpl;
import com.fuyun.integration.service.impl.ReceivedEventQueryServiceImpl;
import org.mapstruct.factory.Mappers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M20 治理 Web/查询/处置面装配：控制器与查询服务 Bean 的集中注册点（backend 宪法 B.1 装配归 app，
 * 本类由 fuyun-app IntegrationConfig @Import 生效；com.fuyun.integration 不在组件扫描范围）。
 *
 * <p>与 {@link MessagingGovernanceConfig} 分工：本类承载对外 REST 面与查询服务；消息治理构件
 * （交换机/队列声明、幂等、死信监听）仍归消息治理配置类，两者不重叠。
 */
@Configuration
@Import({
    DeadLetterServiceImpl.class,
    ReceivedEventQueryServiceImpl.class,
    EventPublicationQueryServiceImpl.class,
    DeadLetterController.class,
    ReceivedEventController.class,
    EventPublicationController.class
})
public class IntegrationWebConfig {

    /**
     * 治理域 MapStruct 转换器 Bean：接口不可经 @Import 注册，经 Mappers.getMapper 装配生成实现
     * （与单测取用同源，SystemWebConfig 的 authConverter/dictConverter 同款）。
     *
     * @return 治理域转换器
     */
    @Bean
    public IntegrationConverter integrationConverter() {
        return Mappers.getMapper(IntegrationConverter.class);
    }
}
