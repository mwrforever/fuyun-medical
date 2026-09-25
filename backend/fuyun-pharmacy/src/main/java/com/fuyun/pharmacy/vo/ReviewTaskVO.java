package com.fuyun.pharmacy.vo;

import com.fuyun.pharmacy.entity.OrderMedication;
import com.fuyun.pharmacy.entity.ReviewTask;

/**
 * 审方任务出参（from(entity, medication) 手写映射，DispenseVO 同型——关键业务字段禁 MapStruct）。
 * 患者摘要=号面（patientId/visitId）：事件契约不携姓名/诊断（脱敏红线），工作台按号核对；
 * 时间类字段不出网（DispenseVO 先例——createdAt/decidedAt 审计时刻不进回显面）。
 *
 * @param id            任务 id（全局 Long→String 出网；即 approve/reject 路径参数与回执 auditNo）
 * @param m04OrderNo    住院医嘱号
 * @param visitId       住院就诊号（I 型 14 位）
 * @param patientId     患者主索引（患者摘要号面）
 * @param freqCode      频次编码，可空（临时医嘱）
 * @param items         药品明细快照 JSON 数组文本（itemSeq/itemCode/itemName/dosage/unit/route/quantity/itemType）
 * @param applyDept     申请科室，可空（CF-6 契约扩展前 NULL）
 * @param applyDoctor   申请医生，可空（同上）
 * @param status        任务状态（ReviewTaskStatus code）
 * @param pharmacistId  审方药师工号，可空（待审 NULL）
 * @param opinion       药师意见，可空
 */
public record ReviewTaskVO(
        Long id,
        String m04OrderNo,
        String visitId,
        Long patientId,
        String freqCode,
        String items,
        String applyDept,
        String applyDoctor,
        String status,
        String pharmacistId,
        String opinion) {

    /**
     * 实体→出参静态工厂（任务行 + 关联快照行双源装配）。
     *
     * @param task       审方任务行，非空
     * @param medication 关联用药快照行（批量映射装配；数据不一致缺失时为 null，号面组件置空）
     * @return 出参，非空
     */
    public static ReviewTaskVO from(ReviewTask task, OrderMedication medication) {
        return new ReviewTaskVO(
                task.getId(),
                medication == null ? null : medication.getM04OrderNo(),
                medication == null ? null : medication.getVisitId(),
                medication == null ? null : medication.getPatientId(),
                medication == null ? null : medication.getFreqCode(),
                medication == null ? null : medication.getItems(),
                task.getApplyDept(),
                task.getApplyDoctor(),
                task.getStatus(),
                task.getPharmacistId(),
                task.getOpinion());
    }
}
