package com.fuyun.ward.vo;

import com.fuyun.ward.entity.WardCallEntity;
import com.fuyun.ward.enums.CallSource;
import com.fuyun.ward.enums.CallStatus;
import com.fuyun.ward.enums.CallType;
import java.time.OffsetDateTime;

/**
 * 呼叫视图对象（呼叫域出网载体）：呼叫行全字段出网（状态机 + 升级计数 + 合并/复位语义留痕）。
 * 实体禁直出（宪法 B.1 出网边界），查询/动作响应统一经 {@link #from} 静态工厂转换。
 *
 * @param id              呼叫行雪花 id，非空
 * @param callNo          呼叫业务号，非空
 * @param wardId          病区 ID，非空
 * @param bedId           床位 ID，可空
 * @param patientId       患者主索引，可空
 * @param deviceId        设备号，可空
 * @param callType        呼叫类型，非空
 * @param source          呼叫来源，非空
 * @param status          呼叫状态，非空
 * @param escalationCount 已升级次数，非空
 * @param processedBy     处理人，可空
 * @param resultSummary   处理结果摘要，可空
 * @param sourceRef       来源引用，可空
 * @param answeredAt      应答时刻，可空
 * @param completedAt     完成时刻，可空
 * @param createdAt       创建时刻（升级时限计算锚），非空
 */
public record WardCallVO(
        Long id,
        String callNo,
        Long wardId,
        Long bedId,
        Long patientId,
        String deviceId,
        CallType callType,
        CallSource source,
        CallStatus status,
        Integer escalationCount,
        String processedBy,
        String resultSummary,
        String sourceRef,
        OffsetDateTime answeredAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt) {

    /**
     * 实体 → 出网视图（唯一转换出口，字段一一对应浅拷贝）。
     *
     * @param entity 呼叫实体，非空；来源：mapper 查询或落库组装
     * @return 呼叫视图，非空
     */
    public static WardCallVO from(WardCallEntity entity) {
        return new WardCallVO(
                entity.getId(),
                entity.getCallNo(),
                entity.getWardId(),
                entity.getBedId(),
                entity.getPatientId(),
                entity.getDeviceId(),
                entity.getCallType(),
                entity.getSource(),
                entity.getStatus(),
                entity.getEscalationCount(),
                entity.getProcessedBy(),
                entity.getResultSummary(),
                entity.getSourceRef(),
                entity.getAnsweredAt(),
                entity.getCompletedAt(),
                entity.getCreatedAt());
    }
}
