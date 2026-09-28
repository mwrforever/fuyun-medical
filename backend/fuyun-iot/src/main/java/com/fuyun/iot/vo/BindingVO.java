package com.fuyun.iot.vo;

import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.enums.BindType;
import com.fuyun.iot.enums.BindingStatus;
import java.time.OffsetDateTime;

/**
 * 设备绑定视图对象（绑定管理域出网载体）：绑定快照五元组全字段 + 生命周期状态 + 操作留痕时间。
 * 实体禁直出（宪法 B.1 出网边界），查询/绑定/解绑响应统一经 {@link #from} 静态工厂转换；
 * visitId 为 CF-3 定长 14 位字符串（V1006 类型改造后形态）。
 *
 * @param id           绑定行雪花 id，非空（落库后回填）
 * @param deviceId     IoTDA 设备标识，非空
 * @param patientId    患者主索引（绑定落行取归一后主档 id），非空
 * @param visitId      住院就诊号（CF-3，14 位字符串），非空
 * @param bedId        床位 id，可空（移动式绑定未落床位）
 * @param wardId       病区 id，非空
 * @param bindType     绑定模式：FIXED/MOBILE，非空
 * @param status       绑定状态机：BOUND/UNBINDING/UNBOUND，非空
 * @param bindReason   绑定原因，可空
 * @param unbindReason 解绑原因，可空（未解绑为空）
 * @param boundBy      绑定操作人，非空（无登录上下文回退 system）
 * @param boundAt      绑定生效时刻（数据库 DEFAULT now() 承担，落库后回读场景有值），可空
 * @param unboundAt    解绑完成时刻，可空
 */
public record BindingVO(
        Long id,
        String deviceId,
        Long patientId,
        String visitId,
        Long bedId,
        Long wardId,
        BindType bindType,
        BindingStatus status,
        String bindReason,
        String unbindReason,
        String boundBy,
        OffsetDateTime boundAt,
        OffsetDateTime unboundAt) {

    /**
     * 实体 → 出网视图（唯一转换出口，字段一一对应浅拷贝）。
     *
     * @param entity 绑定实体，非空；来源：mapper 查询或落库组装
     * @return 绑定视图，非空
     */
    public static BindingVO from(IotBindingEntity entity) {
        return new BindingVO(
                entity.getId(),
                entity.getDeviceId(),
                entity.getPatientId(),
                entity.getVisitId(),
                entity.getBedId(),
                entity.getWardId(),
                entity.getBindType(),
                entity.getStatus(),
                entity.getBindReason(),
                entity.getUnbindReason(),
                entity.getBoundBy(),
                entity.getBoundAt(),
                entity.getUnboundAt());
    }
}
