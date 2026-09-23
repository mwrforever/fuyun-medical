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
 * 患者拆分回流消费侧（patient.patient.split，V105 id 12 既有登记）：patient.patient.merged 的
 * 成对逆事件（M-25），按 restoredPatientId 还原被拆出患者的在区行归属。载荷组件
 * restoredPatientId/survivorPatientId。归 internal/，Bean 注册点 NursingMessagingConfig @Import。
 */
@Slf4j
public class PatientSplitListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final NursingWardPatientMapper wardPatientMapper;

    /**
     * 全参构造器（装配归 NursingMessagingConfig @Import；消费模板多候选 @Qualifier 定绑
     * ——Global Constraints common 模板类多实例红线）。
     *
     * @param consumerSupport   消费模板，非空；定绑 nursingConsumerSupport Bean
     * @param wardPatientMapper 病区患者视图 mapper，非空；patient_id 还原写面
     */
    public PatientSplitListener(
            @Qualifier("nursingConsumerSupport") IdempotentConsumerSupport consumerSupport,
            NursingWardPatientMapper wardPatientMapper) {
        this.consumerSupport = consumerSupport;
        this.wardPatientMapper = wardPatientMapper;
    }

    /**
     * 患者拆分事件入口（标准三段式：NX 去重 → 业务 → PROCESSED 登记）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX + NursingMessagingConstants.EVENT_SUB_PATIENT_PATIENT_SPLIT)
    public void onPatientSplit(Message message) {
        consumerSupport.consume(message, this::handle);
    }

    /**
     * 回流业务体（@RabbitListener 入口仅做 consume 委托）：按载荷 restoredPatientId/survivorPatientId
     * 成对逆映射还原在区行归属；无在区行影响 0 行幂等容忍。
     *
     * @param envelope 已解码信封，非空
     */
    public void handle(EventEnvelope envelope) {
        long restoredPatientId = envelope.payload().path("restoredPatientId").asLong(0L);
        long survivorPatientId = envelope.payload().path("survivorPatientId").asLong(0L);
        // 数据库写操作：merged 的成对逆映射还原（M-25 口径，条件更新幂等）
        int rows = wardPatientMapper.casSplitPatient(survivorPatientId, restoredPatientId);
        log.info(
                "患者拆分回流：eventType={}，restoredPatientId={}，survivorPatientId={}，还原行数={}",
                envelope.eventType(),
                restoredPatientId,
                survivorPatientId,
                rows);
    }
}
