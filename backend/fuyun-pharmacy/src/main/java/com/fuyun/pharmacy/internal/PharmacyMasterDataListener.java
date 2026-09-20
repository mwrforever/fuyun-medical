package com.fuyun.pharmacy.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.patient.api.PatientMergedPayload;
import com.fuyun.patient.api.PatientSplitPayload;
import com.fuyun.pharmacy.cache.PharmacyMasterDataCache;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.system.api.DictPublishedPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * M06 主数据订阅消费侧：字典发布广播（system.dict.published，V5 既有登记）刷新给药途径/频次
 * 版本水位、患者合并/拆分（V105 id 11/12 冻结契约，M-25 成对订阅——凡订阅 merged 必成对订阅
 * split）维护读侧归一映射。载荷 record 经 ObjectMapper 树转值（V105/V5 冻结契约直绑 api 载荷，
 * 禁手工拆字段漂移）；树转失败抛 IllegalStateException 进死信留痕（不合规帧禁静默吞）。
 *
 * <p>条目级字典消费（逐条目装载进本地缓存供审方校验）随 P3 审方引擎接续，W-8 同款前置注记
 * （M01 版本化读接口 {@code GET /api/v1/system/dicts/{type}?version=} 已在位，版本水位即对账锚）；
 * 部署期 V607 预置种子不发该事件——M01 管理面后续变更经广播刷新，语义顺承。幂等边界：eventId
 * 构件幂等（IdempotentConsumerSupport 去重）+ 缓存写幂等（映射覆写/水位覆写/键删除天然可重放）。
 * 类不标 @Component——Bean 注册点 PharmacyMessagingConfig @Import。
 */
@Slf4j
public class PharmacyMasterDataListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final PharmacyMasterDataCache masterDataCache;

    /** 载荷契约 record 树转值（全局定制实例），非空 */
    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 PharmacyMessagingConfig @Import；消费模板多候选 @Qualifier 定绑）。
     *
     * @param consumerSupport  消费模板，非空；定绑 pharmacyConsumerSupport Bean
     * @param masterDataCache  主数据读侧缓存，非空；水位/归一映射唯一写口
     * @param objectMapper     JSON 转换器，非空；载荷 record 反序列化
     */
    public PharmacyMasterDataListener(
            @Qualifier("pharmacyConsumerSupport") IdempotentConsumerSupport consumerSupport,
            PharmacyMasterDataCache masterDataCache,
            ObjectMapper objectMapper) {
        this.consumerSupport = consumerSupport;
        this.masterDataCache = masterDataCache;
        this.objectMapper = objectMapper;
    }

    /**
     * 字典发布广播事件入口（q.pharmacy.system.dict.published）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + PharmacyMessagingConstants.MODULE + "."
                    + PharmacyMessagingConstants.EVENT_SUB_SYSTEM_DICT_PUBLISHED)
    public void onDictPublished(Message message) {
        consumerSupport.consume(message, this::handleDictPublished);
    }

    /**
     * 患者合并事件入口（q.pharmacy.patient.patient.merged，M-25 与 split 成对）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + PharmacyMessagingConstants.MODULE + "."
                    + PharmacyMessagingConstants.EVENT_SUB_PATIENT_MERGED)
    public void onPatientMerged(Message message) {
        consumerSupport.consume(message, this::handlePatientMerged);
    }

    /**
     * 患者拆分事件入口（q.pharmacy.patient.patient.split，M-25 与 merged 成对）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues =
                    "q." + PharmacyMessagingConstants.MODULE + "." + PharmacyMessagingConstants.EVENT_SUB_PATIENT_SPLIT)
    public void onPatientSplit(Message message) {
        consumerSupport.consume(message, this::handlePatientSplit);
    }

    /** 业务体：dict.published→版本水位刷新（白名单过滤归缓存，他类广播忽略） */
    void handleDictPublished(EventEnvelope envelope) {
        DictPublishedPayload payload = toPayload(envelope, DictPublishedPayload.class);
        masterDataCache.refreshDictVersion(payload.dictType(), String.valueOf(payload.version()));
        log.info("字典发布广播消费完成：dictType={}，version={}（版本水位刷新，条目级随 P3）", payload.dictType(), payload.version());
    }

    /** 业务体：patient.merged→写从档→主档归一映射（幂等覆写） */
    void handlePatientMerged(EventEnvelope envelope) {
        PatientMergedPayload payload = toPayload(envelope, PatientMergedPayload.class);
        masterDataCache.onMerged(payload.mergedPatientId(), payload.survivorPatientId());
        log.info("患者合并广播消费完成：merged={}→survivor={}（读侧归一映射已刷新）", payload.mergedPatientId(), payload.survivorPatientId());
    }

    /** 业务体：patient.split→删除恢复从档映射键（逆映射失效） */
    void handlePatientSplit(EventEnvelope envelope) {
        PatientSplitPayload payload = toPayload(envelope, PatientSplitPayload.class);
        masterDataCache.onSplit(payload.restoredPatientId());
        log.info("患者拆分广播消费完成：restored={}（归一映射键已失效）", payload.restoredPatientId());
    }

    /**
     * 信封载荷树转契约 record（三入口共用，字段名与 api record 组件冻结同源）。
     *
     * @param envelope 已解析的合规信封，非空
     * @param type     载荷契约 record 类型，非空
     * @param <T>      契约 record 类型
     * @return 契约载荷，非空
     * @throws IllegalStateException 载荷与契约不符（缺字段/类型错）——按消费失败处置交有界重试
     *                               耗尽进 fy.dlx 留痕，禁止静默吞错
     */
    private <T> T toPayload(EventEnvelope envelope, Class<T> type) {
        try {
            return objectMapper.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "主数据订阅载荷与契约不符：eventType=" + envelope.eventType() + "，payload=" + envelope.payload(), e);
        }
    }
}
