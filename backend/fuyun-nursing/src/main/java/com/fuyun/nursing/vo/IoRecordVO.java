package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.IoRecord;
import java.time.OffsetDateTime;

/**
 * 出入量明细出参（录入/按日清单共用回读面）。quantity 为 string 承载（D-18 口径，数值经
 * toPlainString 输出，禁科学计数法）；sourceRef 为 P2 自动带入方预留回读列（P1 恒空）、
 * shiftCode 班次归属 P1 手工行恒空（列面先行冻结防后续契约变更）。
 *
 * @param id        出入量明细 id
 * @param visitId   住院就诊号
 * @param patientId 患者主索引
 * @param wardId    病区编码
 * @param occurAt   发生时间（服务器时间）
 * @param ioType    出入量类型（IoType code：INTAKE/OUTPUT）
 * @param itemCode  项目 code（IoItemCode 词表）
 * @param itemName  项目名称（冗余展示名，前端直显）
 * @param quantity  数量（数字文本，两位小数，如 "1200.50"）
 * @param unit      单位（缺省 ml）
 * @param source    数据源（IoSource code：P1 仅 MANUAL/PDA）
 * @param sourceRef 来源单据引用（P2 写入方，P1 恒空）
 * @param shiftCode 班次 code（空=未归属班次，P1 手工行恒空）
 * @param recorderId 记录人
 * @param remark    备注（可空）
 */
public record IoRecordVO(
        Long id,
        String visitId,
        Long patientId,
        String wardId,
        OffsetDateTime occurAt,
        String ioType,
        String itemCode,
        String itemName,
        String quantity,
        String unit,
        String source,
        String sourceRef,
        String shiftCode,
        String recorderId,
        String remark) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例；
     * 数量经 toPlainString 承载 D-18 string 出参口径）。
     *
     * @param entity 出入量明细行，非空
     * @return 出入量明细出参，非空
     */
    public static IoRecordVO from(IoRecord entity) {
        return new IoRecordVO(
                entity.getId(),
                entity.getVisitId(),
                entity.getPatientId(),
                entity.getWardId(),
                entity.getOccurAt(),
                entity.getIoType(),
                entity.getItemCode(),
                entity.getItemName(),
                entity.getQuantity().toPlainString(),
                entity.getUnit(),
                entity.getSource(),
                entity.getSourceRef(),
                entity.getShiftCode(),
                entity.getRecorderId(),
                entity.getRemark());
    }
}
