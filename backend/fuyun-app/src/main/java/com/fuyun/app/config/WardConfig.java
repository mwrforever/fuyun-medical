package com.fuyun.app.config;

import com.fuyun.ward.internal.WardMessagingConfig;
import com.fuyun.ward.internal.WardWebConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M16 智慧病房应用模块装配（backend 宪法 B.1 装配归 app，与 IotConfig/InpatientConfig 同模式，
 * 不放宽组件扫描）。
 *
 * <p>P2 PR-2 Task 1 空壳落位：fuyun-ward 依赖随本批进入 fuyun-app 反应堆与 Flyway classpath
 * （V1100+ 迁移目录 spring.flyway.locations 已显式枚举），Modulith 模块图自此含 ward。
 * P2 PR-2 Task 12 {@code @Import} 启用：{@link WardWebConfig}（发号器/四服务/四端点/三消费者
 * ——呼叫状态机/冷链台账/输液看板/体征视图装配集中点）与 {@link WardMessagingConfig}
 * （wardEventSender/wardConsumerSupport 模板 Bean + 三消费队列治理声明 + WardEventPublisher
 * 注册——GC7 跨模块多实例 @Qualifier 定绑锚）；ward 的 mapper 由既有 @MapperScan 按注解自动
 * 覆盖，无需在本壳额外登记。
 */
@Configuration
@Import({WardWebConfig.class, WardMessagingConfig.class})
public class WardConfig {}
