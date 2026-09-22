package com.fuyun.nursing.internal;

import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 健康档案变更回流消费侧（patient.health-summary.updated，V105 id 16 既有登记）：按载荷过敏摘要
 * 刷新病区患者视图 allergy_flag（IN_WARD 行批量置位；详情卡明细另经 AllergyChecker 实时嵌查，
 * 本缓存仅为一览标识位）。载荷组件 patientId/hasAllergy（allergyCodes 不消费不解析）。
 * 归 internal/，Bean 注册点 NursingMessagingConfig @Import。
 */
@Slf4j
public class PatientHealthSummaryListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final NursingWardPatientMapper wardPatientMapper;

    /**
     * 全参构造器（装配归 NursingMessagingConfig @Import；消费模板多候选 @Qualifier 定绑
     * ——Global Constraints common 模板类多实例红线）。
     *
     * @param consumerSupport   消费模板，非空；定绑 nursingConsumerSupport Bean
     * @param wardPatientMapper 病区患者视图 mapper，非空；allergy_flag 刷新写面
     */
    public PatientHealthSummaryListener(
            @Qualifier("nursingConsumerSupport") IdempotentConsumerSupport consumerSupport,
            NursingWardPatientMapper wardPatientMapper) {
        this.consumerSupport = consumerSupport;
        this.wardPatientMapper = wardPatientMapper;
    }

    /**
     * 健康档案变更事件入口（标准三段式：NX 去重 → 业务 → PROCESSED 登记）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX
                    + NursingMessagingConstants.EVENT_SUB_PATIENT_HEALTH_SUMMARY_UPDATED)
    public void onHealthSummaryUpdated(Message message) {
        consumerSupport.consume(message, this::handle);
    }

    /**
     * 回流业务体（@RabbitListener 入口仅做 consume 委托）：按载荷 patientId/hasAllergy 刷新在区行
     * 过敏标识；无在区行影响 0 行幂等容忍（allergyCodes 不消费不解析——标识位缓存仅需布尔面）。
     *
     * @param envelope 已解码信封，非空
     */
    public void handle(EventEnvelope envelope) {
        long patientId = envelope.payload().path("patientId").asLong(0L);
        boolean hasAllergy = envelope.payload().path("hasAllergy").asBoolean(false);
        // 数据库写操作：订阅刷新过敏标识（V105 id 16 载荷，在区行批量置位）
        int rows = wardPatientMapper.updateAllergyFlag(patientId, hasAllergy);
        log.info(
                "健康档案变更回流：eventType={}，patientId={}，hasAllergy={}，刷新行数={}",
                envelope.eventType(),
                patientId,
                hasAllergy,
                rows);
    }
}
