package com.fuyun.billing.config;

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
import com.fuyun.billing.properties.BillingProperties;
import com.fuyun.billing.properties.BillingRefundProperties;
import com.fuyun.billing.service.impl.ChargeItemServiceImpl;
import com.fuyun.billing.service.impl.ChargePriceServiceImpl;
import com.fuyun.billing.service.impl.DailyListServiceImpl;
import com.fuyun.billing.service.impl.DepositServiceImpl;
import com.fuyun.billing.service.impl.InsuranceCallLogServiceImpl;
import com.fuyun.billing.service.impl.InsuranceMappingServiceImpl;
import com.fuyun.billing.service.impl.PricingEngineServiceImpl;
import com.fuyun.billing.service.impl.PricingRuleServiceImpl;
import com.fuyun.billing.service.impl.RefundServiceImpl;
import com.fuyun.billing.service.impl.SettlementServiceImpl;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M13 收费域 Web/服务装配集中点（backend 宪法 B.1 装配归 app：本类由 fuyun-app BillingConfig
 * @Import 生效；十 impl + 十 controller 显式注册，禁组件扫描放宽）。
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
    ChargePriceServiceImpl.class,
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
     * 医保出站网关 Bean（模拟适应器，确定性拆分；凭证/真实通道 P5）。
     *
     * @return 网关实例，singleton 无状态
     */
    @Bean
    public InsuranceGateway insuranceGateway() {
        return new InsuranceSimulatorAdapter();
    }
}
