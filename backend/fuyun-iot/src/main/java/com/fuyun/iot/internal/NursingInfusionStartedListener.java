package com.fuyun.iot.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 护理开始输注消费侧（M14 输液监测联动，P2 PR-3 Task 6）：q.iot.nursing.infusion.started
 * （V800 id 62 登记）——按载荷 patientId/visitId 查 BOUND 态输液泵类设备绑定，建立「告警↔
 * 执行单」患者维度监测关联。
 *
 * <p><b>patientId 直配零新表裁决（brief 冻结）</b>：iot_alarm 无 linkage_no 列、告警引擎无
 * 映射表——告警↔执行单关联不落 iot 侧表（告警载荷 patientId 直配经 nursing 侧在途行匹配）；
 * 本监听器为纯读面（绑定/设备档案查询+关联留痕，零写操作），绑定关系由临床绑定链（bind）
 * 维护，本链路不代建绑定。
 *
 * <p>GC15 operator 桥：MQ 消费链路无登录上下文——入口显式 OperatorContextHolder.set
 * ("SYSTEM") + try/finally clear。载荷以 JsonNode 读（消费侧不依赖生产者 jar，ward 拔针复位
 * 先例）；patientId/executionNo 缺失属不合规帧 ISE 死信留痕。归 internal/：容器驱动入口禁外引；
 * Bean 注册点 IotMessagingConfig @Import。
 */
@Slf4j
public class NursingInfusionStartedListener {

    /** 系统触发面操作者（GC15 桥接口径） */
    private static final String SYSTEM_OPERATOR_TEXT = "SYSTEM";

    /** 输液泵设备类型词表值（总 Spec 5.1 矩阵 15 类；字面量承载防跨模块词表耦合，B.2-2） */
    private static final String DEVICE_TYPE_INFUSION_PUMP = "INFUSION_PUMP";

    private final IdempotentConsumerSupport consumerSupport;

    private final IotBindingMapper bindingMapper;

    private final IotDeviceMapper deviceMapper;

    /**
     * 全参构造器（装配归 IotMessagingConfig @Import；消费模板系 common 基类跨模块多实例 Bean，
     * 必须 @Qualifier 定绑 iotConsumerSupport——GC7 红线；单测按位置构造零改动）。
     *
     * @param consumerSupport 消费模板，非空；定绑 IotMessagingConfig iotConsumerSupport Bean
     * @param bindingMapper   绑定 mapper，非空；BOUND 生效绑定查询面（纯读）
     * @param deviceMapper    设备档案 mapper，非空；泵类设备类型收窄面（纯读）
     */
    public NursingInfusionStartedListener(
            @Qualifier("iotConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IotBindingMapper bindingMapper,
            IotDeviceMapper deviceMapper) {
        this.consumerSupport = consumerSupport;
        this.bindingMapper = bindingMapper;
        this.deviceMapper = deviceMapper;
    }

    /**
     * 开始输注事件消费入口：标准三段式委托（解析信封 → 幂等范式 → 业务 + 成功登记）。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至 q.iot.nursing.infusion.started
     */
    @RabbitListener(queues = IotMessagingConstants.QUEUE_NURSING_INFUSION_STARTED)
    public void onInfusionStarted(Message message) {
        // GC15 operator 桥：系统链路操作者显式落位（finally 清理防线程复用残留）
        OperatorContextHolder.set(SYSTEM_OPERATOR_TEXT);
        try {
            consumerSupport.consume(message, this::handleInfusionStarted);
        } finally {
            OperatorContextHolder.clear();
        }
    }

    /**
     * 消费业务体（包级直驱可测）：载荷读 executionNo/patientId/visitId/bagLabelCode 冻结子集
     * → BOUND 绑定查询 → 设备档案泵类收窄 → 监测关联留痕（patientId 直配锚；零写操作）。
     *
     * @param envelope 已解析的合规信封，非空；载荷契约 V800 id 62 冻结五字段
     * @throws IllegalStateException patientId/executionNo 缺失（不合规帧禁静默吞——死信留痕）时触发
     */
    void handleInfusionStarted(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String executionNo = requireText(envelope, payload, "executionNo");
        JsonNode patientNode = payload.path("patientId");
        if (patientNode.isNull() || patientNode.isMissingNode()) {
            throw new IllegalStateException(
                    "开始输注事件载荷不合规（缺 patientId）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        long patientId = patientNode.asLong();
        String visitId = payload.path("visitId").asText(null);
        // 数据库读操作：该患者 BOUND 生效绑定（visitId 携带时就诊维收窄——uk_iot_binding_device_bound 设备维唯一）
        List<IotBindingEntity> bindings = bindingMapper.selectList(Wrappers.<IotBindingEntity>lambdaQuery()
                .eq(IotBindingEntity::getPatientId, patientId)
                .eq(IotBindingEntity::getStatus, BindingStatus.BOUND)
                .eq(visitId != null && !visitId.isBlank(), IotBindingEntity::getVisitId, visitId));
        // 数据库读操作：绑定设备档案单次 IN 装载（宪法 A.4.3-14），泵类维度收窄——监测关联仅对
        // 输液泵类设备建立（绑定快照可含监护仪等非泵类，无余量监测语义）
        List<String> boundDeviceIds =
                bindings.stream().map(IotBindingEntity::getDeviceId).toList();
        List<String> pumpDevices = boundDeviceIds.isEmpty()
                ? List.of()
                : deviceMapper
                        .selectList(Wrappers.<IotDeviceEntity>lambdaQuery()
                                .in(IotDeviceEntity::getDeviceId, boundDeviceIds)
                                .eq(IotDeviceEntity::getDeviceType, DEVICE_TYPE_INFUSION_PUMP))
                        .stream()
                        .map(IotDeviceEntity::getDeviceId)
                        .toList();
        // 监测关联留痕（patientId 直配锚——告警↔执行单关联由 nursing 侧直配承载，iot 侧零落表）
        log.info(
                "开始输注监测关联建立（patientId 直配锚）：executionNo={}，patientId={}，visitId={}，bagLabelCode={}，BOUND 绑定={}，泵类设备={}，event_id={}",
                executionNo,
                patientId,
                visitId,
                payload.path("bagLabelCode").asText(),
                bindings.size(),
                pumpDevices,
                envelope.eventId());
    }

    /**
     * 载荷必填文本守卫（缺失/空白即不合规帧，显式抛出进死信留痕）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @param field    字段名，非空
     * @return 字段文本值，非空
     */
    private static String requireText(EventEnvelope envelope, JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "开始输注事件载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return value;
    }
}
