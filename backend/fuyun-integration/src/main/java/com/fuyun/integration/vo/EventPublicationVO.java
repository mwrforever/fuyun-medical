package com.fuyun.integration.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 投递台账行出参（GET /api/v1/integration/event-publications）：PR-1a 可靠投递链路的可观测面。
 *
 * @param id              发布记录 ID（框架 UUID），非空
 * @param listenerId      监听器标识（框架记录），非空
 * @param eventType       事件类型全限定名（框架记录；与 event_registry 的 &lt;模块&gt;.&lt;实体&gt;.&lt;动作&gt; 命名不同源）
 * @param publicationDate 发布时刻（业务事务内暂存时间），非空
 * @param completionDate  完成时刻，可空；status=INCOMPLETE 时为空
 * @param status          完成态派生值（COMPLETED/INCOMPLETE），非空
 */
public record EventPublicationVO(
        UUID id,
        String listenerId,
        String eventType,
        OffsetDateTime publicationDate,
        OffsetDateTime completionDate,
        String status) {}
