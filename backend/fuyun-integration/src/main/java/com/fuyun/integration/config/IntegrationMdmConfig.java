package com.fuyun.integration.config;

import com.fuyun.integration.controller.MdmSubscriptionController;
import com.fuyun.integration.service.impl.MdmSubscriptionServiceImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M20 主数据分发治理装配：订阅台账服务与端点的集中注册点（backend 宪法 B.1 装配归 app）。
 *
 * <p>本类由 fuyun-app IntegrationConfig @Import 生效；主数据事件消费侧（分发流水登记）与消费队列
 * 声明随 FU-M20-04 分发链路一并装配（同一配置类内聚，见类后续扩展）。
 */
@Configuration
@Import({MdmSubscriptionServiceImpl.class, MdmSubscriptionController.class})
public class IntegrationMdmConfig {}
