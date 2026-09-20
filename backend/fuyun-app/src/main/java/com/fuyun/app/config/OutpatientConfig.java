package com.fuyun.app.config;

import com.fuyun.outpatient.config.OutpatientMessagingConfig;
import com.fuyun.outpatient.config.OutpatientWebConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M03 门诊模块装配：fuyun-outpatient 配置类引入 Boot 上下文的集中入口（PharmacyConfig 同模式，
 * 不放宽组件扫描）；Web/服务面经 {@link OutpatientWebConfig}（本任务空壳，Task 4 起逐任务追加），
 * 消息面（模板 Bean/发布器/订阅队列与延迟档位声明）经 {@link OutpatientMessagingConfig} 生效；
 * WS 面经 OutpatientWebSocketConfig 随 Task 7 落码后追加。
 */
@Import({OutpatientWebConfig.class, OutpatientMessagingConfig.class})
@Configuration
public class OutpatientConfig {}
