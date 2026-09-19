package com.fuyun.pharmacy.internal;

import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.pharmacy.cache.PharmacyMasterDataCache;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 主数据订阅监听器单测：dict.published 版本水位刷新（给药途径/频次两条目）、patient.merged
 * 写归一映射、patient.split 失效逆映射。业务体为包级 handle 方法，@RabbitListener 入口仅做
 * consume 委托（模板三段式已在 IT 面验证），单测直驱 handle 等价路径（Task 8 监听器单测同款）。
 */
@ExtendWith(MockitoExtension.class)
class PharmacyMasterDataListenerTest {

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private PharmacyMasterDataCache masterDataCache;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 信封手工构造（Task 8 同款形态）：载荷经 createObjectNode 按 api record 冻结字段名承载 */
    private EventEnvelope envelope(String producer, String eventType, com.fasterxml.jackson.databind.JsonNode payload) {
        return new EventEnvelope(
                "it-ev-1", Instant.now(Clock.systemUTC()), producer, eventType, "1", "it-trace", payload);
    }

    @Test
    @DisplayName("dict.published：给药途径/频次两载荷条目分别刷新版本水位（他类白名单外由缓存拒写）")
    void handleDictPublishedRefreshesVersionWatermark() {
        PharmacyMasterDataListener listener =
                new PharmacyMasterDataListener(consumerSupport, masterDataCache, objectMapper);

        listener.handleDictPublished(envelope(
                "system",
                "system.dict.published",
                objectMapper
                        .createObjectNode()
                        .put("dictType", "medication.route")
                        .put("version", 3)));
        listener.handleDictPublished(envelope(
                "system",
                "system.dict.published",
                objectMapper
                        .createObjectNode()
                        .put("dictType", "medication.frequency")
                        .put("version", 1)));

        verify(masterDataCache).refreshDictVersion("medication.route", "3");
        verify(masterDataCache).refreshDictVersion("medication.frequency", "1");
    }

    @Test
    @DisplayName("patient.merged：写从档 12→主档 7 归一映射（读侧查询归一依据）")
    void handlePatientMergedWritesMergeMapping() {
        PharmacyMasterDataListener listener =
                new PharmacyMasterDataListener(consumerSupport, masterDataCache, objectMapper);

        listener.handlePatientMerged(envelope(
                "patient",
                "patient.patient.merged",
                objectMapper.createObjectNode().put("survivorPatientId", 7).put("mergedPatientId", 12)));

        verify(masterDataCache).onMerged(12L, 7L);
    }

    @Test
    @DisplayName("patient.split：删除恢复从档 12 的映射键（与 merged 成对失效，M-25）")
    void handlePatientSplitRemovesMergeMapping() {
        PharmacyMasterDataListener listener =
                new PharmacyMasterDataListener(consumerSupport, masterDataCache, objectMapper);

        listener.handlePatientSplit(envelope(
                "patient",
                "patient.patient.split",
                objectMapper.createObjectNode().put("restoredPatientId", 12).put("survivorPatientId", 7)));

        verify(masterDataCache).onSplit(12L);
    }
}
