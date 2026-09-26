package com.fuyun.iot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.properties.IotdaAdminProperties;
import com.fuyun.iot.registry.IotDeviceRegistry;
import com.fuyun.iot.registry.huawei.HuaweiIotdaRegistry;
import com.fuyun.iot.registry.simulated.SimulatedRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * IoT 设备注册中心双实现装配（P2 PR-2 Task 4）：fuyun.iot.admin.enabled=true → 华为云 IoTDA
 * 实现（HuaweiIotdaRegistry，IoTDA 管理台/生产联动形态）；默认 false → 模拟实现
 * （SimulatedRegistry，dev/test/CI 无云依赖，双条件红线：CI 与单测一律走模拟实现）。
 *
 * <p>双条件形态说明：两个互斥条件各自落在独立嵌套 @Configuration 类（Spring Boot 官方建议
 * 形态——同一配置类内兄弟 @Bean 的 @ConditionalOnMissingBean 评估顺序不可靠）；嵌套类以
 * {@code @Order} 强制 Huawei 先于 Simulated 处理（成员类处理顺序依赖 Class.getDeclaredClasses()
 * 未定义顺序，缺 @Order 时 Simulated 可能先行注册致 Huawei 撞 bean 名，IotRegistryConfigTest
 * 实测兜底）；两 bean 方法同名 iotDeviceRegistry（brief 指定 Huawei bean 名），条件互斥不同时
 * 装配故无命名冲突。Huawei bean 构建前调 {@link IotdaAdminProperties#validateAdminEnabled()}
 * fail-fast（enabled=true 而凭证缺失即阻断装配）；client 惰性构建，装配期零网络请求。
 *
 * <p>装配归 fuyun-app IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）；
 * {@link IotdaAdminProperties} 经本类 {@link EnableConfigurationProperties} 注册（绑定期 Default
 * 组零约束 + 启用组显式 fail-fast，单一注册路径防 @Import 双注册冲突）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IotdaAdminProperties.class)
public class IotRegistryConfig {

    /** 私有构造器：装配入口类禁实例化（嵌套配置类由容器承载） */
    private IotRegistryConfig() {}

    /**
     * 华为云 IoTDA 实现条件装配（enabled=true 生效）：v5 端点 + BasicCredentials 形态，bean 名
     * iotDeviceRegistry（brief 指定）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "fuyun.iot.admin.enabled", havingValue = "true")
    @Order(0)
    static class HuaweiIotdaRegistryConfiguration {

        /**
         * 华为云注册中心 bean。
         *
         * @param properties   管理面配置属性（绑定期已注入），非空；缺失凭证在此 fail-fast
         * @param objectMapper JSON 转换器（Boot 容器实例），非空
         * @return 注册中心实现（华为云 IoTDA），非空
         * @throws IllegalStateException enabled=true 且 endpoint/projectId/accessKey/accessSecret
         *                               任一缺失（validateAdminEnabled 组校验，不携带凭证值）
         */
        @Bean
        IotDeviceRegistry iotDeviceRegistry(IotdaAdminProperties properties, ObjectMapper objectMapper) {
            properties.validateAdminEnabled();
            return new HuaweiIotdaRegistry(properties, objectMapper);
        }
    }

    /**
     * 模拟实现条件装配（默认面，enabled=false 或未配置时生效）：CI/单测/dev/test 无云依赖形态。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(IotDeviceRegistry.class)
    @Order(1)
    static class SimulatedRegistryConfiguration {

        /**
         * 模拟注册中心 bean。
         *
         * @return 注册中心实现（本地模拟），非空
         */
        @Bean
        IotDeviceRegistry iotDeviceRegistry() {
            return new SimulatedRegistry();
        }
    }
}
