package com.fuyun.iot.api.payload;

import java.time.Instant;

/**
 * 绑定变更事件载荷（iot.binding.changed，V1004 id 77 冻结契约）：设备绑定五元组
 * （患者/就诊/床位/病区/绑定模式）变更时广播，M05/M16 据此刷新设备归属视图与播报路由。
 *
 * @param deviceId   IoTDA 设备标识，非空；来源：绑定变更设备
 * @param patientId  患者主索引，可空（解绑后无患者为 null）；来源：变更后绑定档案
 * @param visitId    住院就诊号（CF-3 I 型 visit_id），可空（解绑后无就诊为 null）；来源：变更后绑定档案
 * @param bedId      床位 ID，可空（移动式设备未落床位为 null）；来源：变更后绑定档案
 * @param wardId     病区 ID，非空；来源：变更后绑定档案（播报按病区路由）
 * @param bindType   绑定模式，非空；值域 FIXED/MOBILE（BindType.code）；来源：变更后绑定档案
 * @param changeType 变更方向，非空；值域 BIND/UNBIND（绑定/解绑）；来源：绑定状态迁移方向
 * @param occurredAt 业务发生时刻（UTC），非空；来源：绑定状态迁移时刻
 */
public record BindingChangedPayload(
        String deviceId,
        Long patientId,
        String visitId,
        Long bedId,
        Long wardId,
        String bindType,
        String changeType,
        Instant occurredAt) {}
