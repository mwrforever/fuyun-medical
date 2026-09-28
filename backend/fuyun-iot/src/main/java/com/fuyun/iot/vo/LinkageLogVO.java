package com.fuyun.iot.vo;

import com.fuyun.iot.entity.IotLinkageLogEntity;
import com.fuyun.iot.enums.LinkageActionResult;
import com.fuyun.iot.enums.LinkageActionType;
import com.fuyun.iot.enums.LinkageTriggerSource;
import java.time.OffsetDateTime;

/**
 * 联动执行日志视图对象（联动执行域出网载体，iot_linkage_log 行全字段）：执行留痕 + 结果状态 +
 * 失败重推面（actionResult/retryCount/errorMsg）。实体禁直出（宪法 B.1 出网边界），查询/重推
 * 响应统一经 {@link #from} 转换。
 *
 * @param id            日志行雪花 id，非空
 * @param linkageNo     联动执行业务号，非空
 * @param ruleId        命中联动规则 ID，非空
 * @param triggerSource 触发来源，非空
 * @param triggerRef    触发来源引用（告警号等），非空
 * @param actionType    动作类型，非空
 * @param actionResult  动作执行结果（SUCCESS/FAILED/PENDING），非空
 * @param retryCount    累计重试次数，非空
 * @param errorMsg      失败原因/暂存注记，可空
 * @param executedAt    执行时刻（PENDING 行为暂存时点），非空
 * @param createdAt     落行时刻，非空
 */
public record LinkageLogVO(
        Long id,
        String linkageNo,
        Long ruleId,
        LinkageTriggerSource triggerSource,
        String triggerRef,
        LinkageActionType actionType,
        LinkageActionResult actionResult,
        Integer retryCount,
        String errorMsg,
        OffsetDateTime executedAt,
        OffsetDateTime createdAt) {

    /**
     * 实体 → 出网视图（唯一转换出口，字段一一对应浅拷贝）。
     *
     * @param entity 联动日志实体，非空；来源：mapper 查询
     * @return 日志视图，非空
     */
    public static LinkageLogVO from(IotLinkageLogEntity entity) {
        return new LinkageLogVO(
                entity.getId(),
                entity.getLinkageNo(),
                entity.getRuleId(),
                entity.getTriggerSource(),
                entity.getTriggerRef(),
                entity.getActionType(),
                entity.getActionResult(),
                entity.getRetryCount(),
                entity.getErrorMsg(),
                entity.getExecutedAt(),
                entity.getCreatedAt());
    }
}
