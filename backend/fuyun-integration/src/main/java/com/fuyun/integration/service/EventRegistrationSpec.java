package com.fuyun.integration.service;

/**
 * 事件登记参数对象（A.7 参数对象化）：event_registry 契约登记入参的整体封装。
 *
 * @param eventType         事件类型 {@code <模块>.<实体>.<动作>}，非空；来源：发布方事件契约定义
 * @param producerModule    生产模块域标识，非空；来源：发布方模块
 * @param payloadDesc       载荷结构说明（冻结契约摘要），非空；来源：发布方契约文档
 * @param subscriberModules 订阅模块清单（逗号分隔），允许为空（空串=待订阅）；
 *                          broadcast=true 时本字段被忽略，来源：发布方登记时的已知订阅方
 * @param broadcast         是否零订阅广播事件；true 时订阅清单记为 broadcast 标记（R6-13）
 */
public record EventRegistrationSpec(
        String eventType, String producerModule, String payloadDesc, String subscriberModules, boolean broadcast) {}
