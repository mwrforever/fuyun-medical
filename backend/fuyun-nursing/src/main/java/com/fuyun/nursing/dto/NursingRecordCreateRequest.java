package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 护理记录创建入参（POST /api/v1/nursing/nursing-records）。patientId/wardId 不设入参组件
 * ——由 IWardMetaService 在区行服务端装配（不信客户端）；recordTime 不设入参组件——文书
 * 业务时间一律服务器时间（GC25 护理文书红线）；记录号由 NursingSeqGate 服务端签发。
 *
 * @param visitId     住院就诊号（I 型 14 位），必填；来源：操作者工作站当前患者
 * @param recordClass 记录类别（RecordClass code，空缺省 GENERAL；非法值 NS-1019），可空；来源：操作者选择
 * @param observation 病情观察（结构化段），可空；来源：操作者录入
 * @param measures    护理措施（结构化段），可空；来源：操作者录入
 * @param evaluation  效果评价（结构化段），可空；来源：操作者录入
 * @param freeText    自由文本补充，可空；来源：操作者录入
 */
public record NursingRecordCreateRequest(
        @NotBlank(message = "visitId 不能为空") String visitId,
        String recordClass,
        String observation,
        String measures,
        String evaluation,
        String freeText) {}
