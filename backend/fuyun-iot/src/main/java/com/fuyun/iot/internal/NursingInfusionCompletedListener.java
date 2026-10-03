package com.fuyun.iot.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.dto.UnbindDeviceRequest;
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.service.IBindingService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 护理拔针/输注结束消费侧（M14 输液监测联动，P2 PR-3 Task 6）：q.iot.nursing.infusion.
 * completed（V800 id 63 登记）——按载荷 patientId 停止该患者维度的输液监测。
 *
 * <p><b>停止监测落点（brief「先实测后定」禁新表禁迁移裁决）</b>：实测告警引擎五项抑制均为
 * rule/device 维度（同源聚合/离线抑制/风暴抑制/恢复带/升级惰性），无 patient 维度抑制入口
 * ——按 brief 预案落「绑定关系监测暂停」标记进既有表列：对该患者 BOUND 态输液泵类设备绑定
 * 经 {@link IBindingService#unbind} 唯一写入口执行解绑迁移（iot_binding.status BOUND→
 * UNBOUND + unbind_reason='INFUSION_COMPLETED'，既有列零新增）。静默精确语义：解绑后泵类
 * 遥测告警不再携带该 patientId（告警实体绑定快照冗余面），nursing 侧 patientId 直配升级挂单
 * 自然失配——余量告急规则对该患者的挂单升级静默；设备级告警行本身保留（病区路由经设备档案
 * 兜底），其止发需告警引擎引入 patient 维度抑制面，超出本任务薄改边界（实测结论随 PR 描述
 * 申报）。再次输注由临床绑定链重新 bind，监测自然恢复。
 *
 * <p>GC15 operator 桥：入口 OperatorContextHolder.set("SYSTEM") + try/finally clear。
 * 载荷以 JsonNode 读（不依赖生产者 jar，ward 拔针复位同事件同款先例）；patientId/executionNo
 * 缺失属不合规帧 ISE 死信留痕。解绑 CAS 并发落败异常上抛交容器有界重试（重复消费幂等——
 * 已解绑设备不再命中 BOUND 查询）。归 internal/：容器驱动入口禁外引；Bean 注册点
 * IotMessagingConfig @Import。
 */
@Slf4j
public class NursingInfusionCompletedListener {

    /** 系统触发面操作者（GC15 桥接口径） */
    private static final String SYSTEM_OPERATOR_TEXT = "SYSTEM";

    /** 输液泵设备类型词表值（总 Spec 5.1 矩阵 15 类；字面量承载防跨模块词表耦合，B.2-2） */
    private static final String DEVICE_TYPE_INFUSION_PUMP = "INFUSION_PUMP";

    /** 拔针驱动解绑原因留痕（V400 unbind_reason 列承载，系统触发面固定文案） */
    private static final String UNBIND_REASON_INFUSION_COMPLETED = "INFUSION_COMPLETED";

    private final IdempotentConsumerSupport consumerSupport;

    private final IotBindingMapper bindingMapper;

    private final IotDeviceMapper deviceMapper;

    private final IBindingService bindingService;

    /**
     * 全参构造器（装配归 IotMessagingConfig @Import；消费模板系 common 基类跨模块多实例 Bean，
     * 必须 @Qualifier 定绑 iotConsumerSupport——GC7 红线；单测按位置构造零改动）。
     *
     * @param consumerSupport 消费模板，非空；定绑 IotMessagingConfig iotConsumerSupport Bean
     * @param bindingMapper   绑定 mapper，非空；BOUND 泵类绑定查询面（纯读）
     * @param deviceMapper    设备档案 mapper，非空；泵类设备类型收窄面（纯读）
     * @param bindingService  绑定域服务，非空；解绑迁移唯一写入口（双 CAS+事件发布）
     */
    public NursingInfusionCompletedListener(
            @Qualifier("iotConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IotBindingMapper bindingMapper,
            IotDeviceMapper deviceMapper,
            IBindingService bindingService) {
        this.consumerSupport = consumerSupport;
        this.bindingMapper = bindingMapper;
        this.deviceMapper = deviceMapper;
        this.bindingService = bindingService;
    }

    /**
     * 输注结束事件消费入口：标准三段式委托（解析信封 → 幂等范式 → 业务 + 成功登记）。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至 q.iot.nursing.infusion.completed
     */
    @RabbitListener(queues = IotMessagingConstants.QUEUE_NURSING_INFUSION_COMPLETED)
    public void onInfusionCompleted(Message message) {
        // GC15 operator 桥：系统链路操作者显式落位（finally 清理防线程复用残留）
        OperatorContextHolder.set(SYSTEM_OPERATOR_TEXT);
        try {
            consumerSupport.consume(message, this::handleInfusionCompleted);
        } finally {
            OperatorContextHolder.clear();
        }
    }

    /**
     * 消费业务体（包级直驱可测）：载荷读 executionNo/patientId/endedAt 冻结子集 → BOUND
     * 泵类绑定解析 → 逐台解绑（监测暂停标记进既有列）。
     *
     * @param envelope 已解析的合规信封，非空；载荷契约 V800 id 63 冻结四字段
     * @throws IllegalStateException patientId/executionNo 缺失（不合规帧禁静默吞——死信留痕）时触发
     */
    void handleInfusionCompleted(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String executionNo = requireText(envelope, payload, "executionNo");
        JsonNode patientNode = payload.path("patientId");
        if (patientNode.isNull() || patientNode.isMissingNode()) {
            throw new IllegalStateException(
                    "输注结束事件载荷不合规（缺 patientId）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        long patientId = patientNode.asLong();
        // 数据库读操作：该患者 BOUND 生效绑定（解绑候选集——uk_iot_binding_device_bound 设备维唯一）
        List<IotBindingEntity> bindings = bindingMapper.selectList(Wrappers.<IotBindingEntity>lambdaQuery()
                .eq(IotBindingEntity::getPatientId, patientId)
                .eq(IotBindingEntity::getStatus, BindingStatus.BOUND));
        if (bindings.isEmpty()) {
            // 无生效绑定（未绑泵/已解绑重复投递）：监测面已空，幂等留痕收口
            log.info(
                    "输注结束停止监测零命中（无 BOUND 绑定）：executionNo={}，patientId={}，event_id={}",
                    executionNo,
                    patientId,
                    envelope.eventId());
            return;
        }
        // 数据库读操作：绑定设备档案单次 IN 装载（宪法 A.4.3-14），泵类维度收窄——仅泵类解绑
        // （监护仪等非泵类绑定为持续生命体征监测面，不随拔针解除）
        List<String> boundDeviceIds =
                bindings.stream().map(IotBindingEntity::getDeviceId).toList();
        List<String> pumpDeviceIds = deviceMapper
                .selectList(Wrappers.<IotDeviceEntity>lambdaQuery()
                        .in(IotDeviceEntity::getDeviceId, boundDeviceIds)
                        .eq(IotDeviceEntity::getDeviceType, DEVICE_TYPE_INFUSION_PUMP))
                .stream()
                .map(IotDeviceEntity::getDeviceId)
                .toList();
        if (pumpDeviceIds.isEmpty()) {
            log.info(
                    "输注结束停止监测零命中（BOUND 无泵类设备）：executionNo={}，patientId={}，bound={}，event_id={}",
                    executionNo,
                    patientId,
                    boundDeviceIds,
                    envelope.eventId());
            return;
        }
        // 解绑迁移（绑定域唯一写入口——双 CAS+binding.changed 事件；并发落败异常上抛交容器重试，
        // 重复消费经 BOUND 查询谓词幂等）
        UnbindDeviceRequest request = new UnbindDeviceRequest(UNBIND_REASON_INFUSION_COMPLETED);
        for (String deviceId : pumpDeviceIds) {
            bindingService.unbind(deviceId, request);
        }
        log.info(
                "输注结束停止监测完成（泵类绑定解绑，患者维度监测暂停）：executionNo={}，patientId={}，解绑设备={}，event_id={}",
                executionNo,
                patientId,
                pumpDeviceIds,
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
                    "输注结束事件载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return value;
    }
}
