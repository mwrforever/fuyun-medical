package com.fuyun.integration.api;

/**
 * 消费队列声明契约：一个声明 = 一个消费者模块订阅一个事件类型（M20 治理约定）。
 *
 * @param consumerModule 消费者模块域标识（如 it/system），非空小写；来源：订阅方模块装配代码
 * @param eventType      订阅的事件类型（= routing key，{@code <模块>.<实体>.<动作>}），
 *                       小写点分 ≥3 段且须已在 event_registry 登记；来源：发布方事件契约
 */
public record ConsumerQueueSpec(String consumerModule, String eventType) {}
