package com.fuyun.ward.dto;

import com.fuyun.ward.enums.CallSource;
import com.fuyun.ward.enums.CallType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 手工呼叫创建请求（POST /api/v1/ward/ward-calls 请求体）：护士 pad/工作站入口。
 *
 * @param wardId    病区 ID，非空；来源：登录上下文病区或请求体
 * @param bedId     床位 ID，非空（手工创建必有床位上下文；设备源落行走事件链不经本请求）；来源：请求体
 * @param patientId 患者主索引，非空（手工创建按床位患者关联）；来源：请求体
 * @param deviceId  设备号（床头分机等来源设备，NURSE_PAD 手工场景可缺省），可空；来源：请求体
 * @param callType  呼叫类型，非空；来源：请求体
 * @param source    呼叫来源，非空（设备源 IOT 不经本入口——事件消费落行）；来源：请求体
 * @param sourceRef 来源引用（服务单号等业务引用，可空）；来源：请求体
 */
public record CreateWardCallRequest(
        @NotNull(message = "wardId 不能为空") Long wardId,
        @NotNull(message = "bedId 不能为空") Long bedId,
        @NotNull(message = "patientId 不能为空") Long patientId,
        @Size(max = 64, message = "deviceId 最长 64 字符") String deviceId,
        @NotNull(message = "callType 不能为空") CallType callType,
        @NotNull(message = "source 不能为空") CallSource source,
        @Size(max = 64, message = "sourceRef 最长 64 字符") String sourceRef) {

    /**
     * 紧凑构造校验：设备源（IOT）拒绝走手工入口（设备源呼叫经 iot 事件消费落行，防双通道重复落行）。
     */
    public CreateWardCallRequest {
        if (source == CallSource.IOT) {
            throw new IllegalArgumentException("设备源呼叫经事件消费落行，禁止手工入口创建");
        }
    }
}
