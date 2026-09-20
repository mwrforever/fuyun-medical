package com.fuyun.billing.config;

import com.fuyun.billing.api.PrescriptionFeePort;
import com.fuyun.billing.controller.ChargeItemController;
import com.fuyun.billing.controller.DailyListController;
import com.fuyun.billing.controller.DepositController;
import com.fuyun.billing.controller.FeeController;
import com.fuyun.billing.controller.InsuranceController;
import com.fuyun.billing.controller.InsuranceMappingController;
import com.fuyun.billing.controller.PriceAdjustmentController;
import com.fuyun.billing.controller.PricingRuleController;
import com.fuyun.billing.controller.RefundController;
import com.fuyun.billing.controller.SettlementController;
import com.fuyun.billing.gateway.InsuranceGateway;
import com.fuyun.billing.gateway.InsuranceSimulatorAdapter;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.properties.BillingProperties;
import com.fuyun.billing.properties.BillingRefundProperties;
import com.fuyun.billing.service.IChargeItemService;
import com.fuyun.billing.service.IInsuranceMappingService;
import com.fuyun.billing.service.IPricingEngineService;
import com.fuyun.billing.service.impl.ChargeItemServiceImpl;
import com.fuyun.billing.service.impl.ChargePriceServiceImpl;
import com.fuyun.billing.service.impl.DailyListServiceImpl;
import com.fuyun.billing.service.impl.DepositServiceImpl;
import com.fuyun.billing.service.impl.InsuranceCallLogServiceImpl;
import com.fuyun.billing.service.impl.InsuranceMappingServiceImpl;
import com.fuyun.billing.service.impl.PrescriptionFeePortImpl;
import com.fuyun.billing.service.impl.PricingEngineServiceImpl;
import com.fuyun.billing.service.impl.PricingRuleServiceImpl;
import com.fuyun.billing.service.impl.RefundServiceImpl;
import com.fuyun.billing.service.impl.SettlementServiceImpl;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M13 收费域 Web/服务装配集中点（backend 宪法 B.1 装配归 app：本类由 fuyun-app BillingConfig
 * @Import 生效；九 impl + 十 controller 经 @Import 显式注册，取价服务因需注入取价时钟
 * {@link ChargePriceServiceImpl} 改由本类 @Bean 显式构造，禁组件扫描放宽）。
 *
 * <p>医保网关形态红线（已裁决 3）：本 PR 仅注册模拟适应器为唯一 {@link InsuranceGateway} 实现
 * （Task 15 注记「@Primary/dev 装配」收敛为单实现直接注册，无第二实现即无 @Primary 需求）；
 * P5 真实通道接入时以 @ConditionalOnProperty 双实现切换，业务侧零改。
 */
@Configuration
@EnableConfigurationProperties({BillingProperties.class, BillingRefundProperties.class})
@Import({
    ChargeItemServiceImpl.class,
    InsuranceMappingServiceImpl.class,
    PricingRuleServiceImpl.class,
    PricingEngineServiceImpl.class,
    SettlementServiceImpl.class,
    RefundServiceImpl.class,
    DepositServiceImpl.class,
    DailyListServiceImpl.class,
    InsuranceCallLogServiceImpl.class,
    ChargeItemController.class,
    InsuranceMappingController.class,
    PricingRuleController.class,
    PriceAdjustmentController.class,
    FeeController.class,
    SettlementController.class,
    RefundController.class,
    DepositController.class,
    DailyListController.class,
    InsuranceController.class
})
public class BillingWebConfig {

    /**
     * 取价服务 Bean（价格版本化与计费快照）：装配期注入 UTC 系统时钟
     * （Clock.systemUTC()，SystemWebConfig 同款形态）作为价格区间判定基准——不注册全局 Clock Bean，
     * 避免与 IotAmqpConfig 条件装配（fuyun.iot.amqp.enabled）的 iotAmqpClock 形成同类型注入歧义
     * （IoT 消费链按类型注入 Clock，多候选即启动失败）；时钟经构造器注入，单测另建实例注入固定时钟。
     *
     * @param mappingService 医保对照服务，非空；来源：容器 Bean
     * @param itemService    收费项目服务，非空；来源：容器 Bean
     * @param events         应用事件发布器，非空；来源：容器 Bean
     * @return 取价服务实例，singleton 无状态（时间源为只读 Clock）
     */
    @Bean
    public ChargePriceServiceImpl chargePriceServiceImpl(
            IInsuranceMappingService mappingService, IChargeItemService itemService, ApplicationEventPublisher events) {
        return new ChargePriceServiceImpl(mappingService, itemService, events, Clock.systemUTC());
    }

    /**
     * 医保出站网关 Bean（模拟适应器，确定性拆分；凭证/真实通道 P5）。
     *
     * @return 网关实例，singleton 无状态
     */
    @Bean
    public InsuranceGateway insuranceGateway() {
        return new InsuranceSimulatorAdapter();
    }

    /**
     * 处方联动费用作废端口（M06 进程内对接面）：引擎 cancel 语义复用装配点。
     *
     * @param feeRecordMapper 费用行 mapper，非空
     * @param engine          计价引擎，非空
     * @return 端口实现，singleton
     */
    @Bean
    public PrescriptionFeePort prescriptionFeePort(FeeRecordMapper feeRecordMapper, IPricingEngineService engine) {
        return new PrescriptionFeePortImpl(feeRecordMapper, engine);
    }
}
