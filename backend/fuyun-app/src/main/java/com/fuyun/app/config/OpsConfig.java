package com.fuyun.app.config;

import com.fuyun.ops.config.OpsWebConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M19 运营模块装配：fuyun-ops 配置类引入 Boot 上下文的集中入口（OutpatientConfig 同模式，
 * 不放宽组件扫描）。批次 2 册 2 首切片：Web/服务面经 {@link OpsWebConfig}（工作台聚合服务
 * 与两读端点）；本模块零消息面零 WS 面（/ws/ops 自建端点属后续批次，当前复用既有三 STOMP
 * 端点主题）。
 */
@Import(OpsWebConfig.class)
@Configuration
public class OpsConfig {}
