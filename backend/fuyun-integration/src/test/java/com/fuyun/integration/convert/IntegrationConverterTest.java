package com.fuyun.integration.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 治理域转换器单测：死信实体 → 列表行/详情出参映射，列表行载荷只出预览（全文仅详情端点）。
 */
class IntegrationConverterTest {

    @Test
    @DisplayName("列表行出参：载荷字段只出头部预览，摘要与身份字段原样承载，其余字符串字段不误截断")
    void listRowCarriesPayloadPreviewOnly() {
        DeadLetter entity = sample("y".repeat(500));

        DeadLetterVO vo = IntegrationConverter.INSTANCE.toDeadLetterVO(entity);

        assertThat(vo.payloadPreview()).hasSize(MessagingConstants.DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH);
        assertThat(vo.id()).isEqualTo(7L);
        assertThat(vo.eventId()).isEqualTo("b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60");
        assertThat(vo.replayCount()).isZero();
        // 仅 payloadPreview 参与截断：超预览上限的 fail_reason（列宽 VARCHAR(1000)）必须原样透出
        assertThat(vo.failReason()).isEqualTo(entity.getFailReason());
        assertThat(vo.status()).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_PENDING);
    }

    @Test
    @DisplayName("详情出参：载荷全文承载不被预览上限截断，短载荷预览不截断")
    void detailCarriesFullPayload() {
        DeadLetter entity = sample("{\"eventId\":\"" + "x".repeat(500) + "\"}");

        DeadLetterDetailVO detail = IntegrationConverter.INSTANCE.toDeadLetterDetailVO(entity);

        // 详情端点承载全文：载荷超预览上限（200 字符）时也必须完整透出（运维诊断看全文）
        assertThat(detail.payloadBody()).isEqualTo(entity.getPayloadBody());
        assertThat(IntegrationConverter.INSTANCE.toDeadLetterVO(entity).payloadPreview())
                .hasSize(MessagingConstants.DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH);
    }

    /**
     * 构造死信样本行。
     *
     * @param payloadBody 载荷原文，非空
     * @return 死信实体
     */
    private DeadLetter sample(String payloadBody) {
        DeadLetter entity = new DeadLetter();
        entity.setId(7L);
        entity.setSourceQueue("q.it.system.dict.published");
        entity.setRoutingKey("system.dict.published");
        entity.setEventType("system.dict.published");
        entity.setEventId("b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60");
        entity.setPayloadBody(payloadBody);
        entity.setPayloadDigest("d".repeat(64));
        // fail_reason 列宽 VARCHAR(1000)，构造超预览上限（200 字符）样本以守护"仅 payloadPreview 截断"
        entity.setFailReason("消费死信：reason=rejected，" + "r".repeat(300));
        entity.setFirstDeadAt(OffsetDateTime.parse("2026-09-15T01:02:03Z"));
        entity.setStatus(MessagingConstants.DEAD_LETTER_STATUS_PENDING);
        entity.setReplayCount(0);
        return entity;
    }
}
