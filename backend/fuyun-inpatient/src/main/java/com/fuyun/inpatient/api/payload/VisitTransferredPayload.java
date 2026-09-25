package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 患者转科/转床事件载荷（inpatient.visit.transferred，V800 id 49 冻结契约）：护理单元变更
 * 编排（转科四阶段/转床轻量路径）事务提交后发布，M05 据此刷新病区患者列表与未执行执行单
 * 重定向、M13 记录费用归属切分点、M14 强制解绑设备。脱敏红线：禁患者姓名/诊断文本。
 *
 * @param visitId      住院就诊号（I 型 14 位），非空；来源：编排起始就诊行
 * @param patientId    患者主索引，非空；来源：就诊行归一主档
 * @param fromWardId   转出病区编码，非空；来源：编排起始就诊行 current_ward_id
 * @param fromBedId    转出床位 id，非空；来源：编排起始就诊行 current_bed_id
 * @param toWardId     转入病区编码，非空；来源：编排入参（转床路径=原病区）
 * @param toBedId      转入床位 id，非空；来源：编排入参
 * @param transferredAt 转移完成时点（UTC，应用服务器时钟），非空
 */
public record VisitTransferredPayload(
        String visitId,
        long patientId,
        String fromWardId,
        long fromBedId,
        String toWardId,
        long toBedId,
        Instant transferredAt) {}
