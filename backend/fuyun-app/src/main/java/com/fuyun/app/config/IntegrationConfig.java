package com.fuyun.app.config;

import com.fuyun.integration.config.IntegrationWebConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M20 集成平台治理装配：将 fuyun-integration 的治理配置类引入 Boot 上下文的集中入口
 * （backend 宪法 B.1 装配归 app，与 SystemConfig/IotConfig 同模式，不放宽组件扫描）。
 *
 * <p>治理 Web/查询/处置面经 {@link IntegrationWebConfig} 生效（主数据分发订阅与流水治理配置类
 * 随 FU-M20-04 交付时在本 @Import 清单内追加）。
 */
@Configuration
@Import(IntegrationWebConfig.class)
public class IntegrationConfig {}
