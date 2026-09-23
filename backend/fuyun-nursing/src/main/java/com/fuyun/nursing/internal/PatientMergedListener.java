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
 * 患者合并回流消费侧（patient.patient.merged，V105 id 11 既有登记）：将被合并从档的在区行
 * patient_id 收敛为存活主档（CF-3 归一语义，@Update 条件更新）。成对口径（M-25）：本事件订阅
 * 与 patient.patient.split 成对登记（Spec :196/:262）。载荷组件 survivorPatientId/mergedPatientId。
 * 归 internal/，Bean 注册点 NursingMessagingConfig @Import。
 */
@Slf4j
public class PatientMergedListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final NursingWardPatientMapper wardPatientMapper;

    /**
     * 全参构造器（装配归 NursingMessagingConfig @Import；消费模板多候选 @Qualifier 定绑
     * ——Global Constraints common 模板类多实例红线）。
     *
     * @param consumerSupport   消费模板，非空；定绑 nursingConsumerSupport Bean
     * @param wardPatientMapper 病区患者视图 mapper，非空；patient_id 收敛写面
     */
    public PatientMergedListener(
            @Qualifier("nursingConsumerSupport") IdempotentConsumerSupport consumerSupport,
            NursingWardPatientMapper wardPatientMapper) {
        this.consumerSupport = consumerSupport;
        this.wardPatientMapper = wardPatientMapper;
    }

    /**
     * 患者合并事件入口（标准三段式：NX 去重 → 业务 → PROCESSED 登记）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues =
                    NursingMessagingConstants.QUEUE_PREFIX + NursingMessagingConstants.EVENT_SUB_PATIENT_PATIENT_MERGED)
    public void onPatientMerged(Message message) {
        consumerSupport.consume(message, this::handle);
    }

    /**
     * 回流业务体（@RabbitListener 入口仅做 consume 委托）：按载荷 survivorPatientId/mergedPatientId
     * 收敛在区行主档；无从档在区行影响 0 行幂等容忍。
     *
     * @param envelope 已解码信封，非空
     */
    public void handle(EventEnvelope envelope) {
        long survivorPatientId = envelope.payload().path("survivorPatientId").asLong(0L);
        long mergedPatientId = envelope.payload().path("mergedPatientId").asLong(0L);
        // 数据库写操作：被合并从档在区行收敛主档（CF-3 归一语义，条件更新幂等）
        int rows = wardPatientMapper.casMergePatient(mergedPatientId, survivorPatientId);
        log.info(
                "患者合并回流：eventType={}，mergedPatientId={}，survivorPatientId={}，收敛行数={}",
                envelope.eventType(),
                mergedPatientId,
                survivorPatientId,
                rows);
    }
}
