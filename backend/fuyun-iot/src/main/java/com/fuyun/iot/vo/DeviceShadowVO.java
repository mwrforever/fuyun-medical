package com.fuyun.iot.vo;

import com.fuyun.iot.registry.DeviceShadow;
import java.util.Map;

/**
 * 设备影子视图（GET /api/v1/iot/devices/{deviceId}/shadow 出参，record 不可变）：注册中心
 * 影子双面直通映射（desired/reported，键为属性名、值为属性值原文），不做二次加工。
 *
 * @param desired  期望面（云端下发待设备确认的属性集），非空（无数据为空 map）
 * @param reported 上报面（设备实际上报的属性集），非空（无数据为空 map）
 */
public record DeviceShadowVO(Map<String, Object> desired, Map<String, Object> reported) {

    /**
     * 注册中心影子 → 视图直通工厂。
     *
     * @param shadow 注册中心影子载体，非空
     * @return 影子视图（双面引用直传），非空
     */
    public static DeviceShadowVO from(DeviceShadow shadow) {
        return new DeviceShadowVO(shadow.desired(), shadow.reported());
    }
}
