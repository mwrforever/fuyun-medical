package com.fuyun.integration.vo;

import java.time.OffsetDateTime;

/**
 * 主数据订阅矩阵行出参（GET /api/v1/integration/mdm-subscriptions）：主题 × 订阅方 × 版本 × 对账状态。
 *
 * @param id               订阅记录 ID（雪花 ID），非空；JSON 输出为字符串
 * @param topic            主数据主题，非空
 * @param subscriberModule 订阅方模块域标识，非空
 * @param syncMode         同步方式，非空
 * @param lastVersion      订阅方已同步版本号，可空（尚未对账/从未同步）
 * @param lastSyncAt       最近同步时刻，可空
 * @param lastReconAt      最近对账时刻，可空
 * @param reconStatus      对账状态（PENDING 待对账），非空
 */
public record MdmSubscriptionVO(
        Long id,
        String topic,
        String subscriberModule,
        String syncMode,
        Long lastVersion,
        OffsetDateTime lastSyncAt,
        OffsetDateTime lastReconAt,
        String reconStatus) {}
