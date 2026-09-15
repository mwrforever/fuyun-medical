package com.fuyun.integration.vo;

import java.time.OffsetDateTime;

/**
 * 事件契约台账行出参（GET /api/v1/integration/event-registry）：主题 × 订阅方 × 状态矩阵读面。
 *
 * @param id                契约行 ID（雪花 ID），非空；JSON 输出为字符串
 * @param eventType         事件类型 &lt;模块&gt;.&lt;实体&gt;.&lt;动作&gt;，非空
 * @param producerModule    生产模块域标识，非空
 * @param payloadDesc       载荷结构说明（冻结契约摘要），非空
 * @param subscriberModules 订阅模块清单（逗号分隔；broadcast=零订阅广播标记），非空
 * @param status            契约状态：ACTIVE 生效 / DEPRECATED 废止，非空
 * @param registeredAt      业务登记时间，非空
 */
public record EventRegistryVO(
        Long id,
        String eventType,
        String producerModule,
        String payloadDesc,
        String subscriberModules,
        String status,
        OffsetDateTime registeredAt) {}
