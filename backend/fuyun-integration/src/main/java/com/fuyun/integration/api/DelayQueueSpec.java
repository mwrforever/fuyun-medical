package com.fuyun.integration.api;

import java.time.Duration;

/**
 * 延迟队列声明契约：一条队列一个延迟档位（backend 宪法 A.5-7，不引入 delayed-message 插件）。
 *
 * @param business         业务名（队列名 delay.&lt;business&gt;），非空小写；来源：声明方模块
 * @param ttl              该档位延迟时长，必须 &gt; 0 且毫秒值不超出 int 上界；到期消息经 DLX 参数
 *                         回投 fy.topic；来源：业务延迟语义定义
 * @param targetRoutingKey 到期转发回 fy.topic 的目标路由键（通常为目标事件类型），非空；来源：业务延迟语义定义
 */
public record DelayQueueSpec(String business, Duration ttl, String targetRoutingKey) {}
