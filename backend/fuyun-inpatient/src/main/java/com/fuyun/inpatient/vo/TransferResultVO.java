package com.fuyun.inpatient.vo;

import java.time.OffsetDateTime;

/**
 * 转科/转床编排出参（POST /api/v1/inpatient/visits/{visitId}/transfer 与 /change-bed 共用；
 * 实体禁直出——出网边界唯一出口）：前后定位面与完成时点，供护士站确认与 M05/M13/M14 事件
 * 对账（事件载荷 VisitTransferredPayload 同源字段）。
 *
 * @param visitId       住院就诊号（I 型 14 位）
 * @param fromWardId    转出病区编码
 * @param fromBedId     转出床位 id
 * @param toWardId      转入病区编码（转床路径=原病区）
 * @param toBedId       转入床位 id
 * @param transferredAt 转移完成时点（应用服务器时钟，与事件 transferredAt 同源）
 */
public record TransferResultVO(
        String visitId,
        String fromWardId,
        Long fromBedId,
        String toWardId,
        Long toBedId,
        OffsetDateTime transferredAt) {}
