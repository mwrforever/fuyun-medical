package com.fuyun.inpatient.api.payload;

import java.util.List;

/**
 * 医嘱开立事件载荷（inpatient.order.created，V901 id 66 冻结契约）：医嘱开立保存后发布，
 * routing key 携带类型子键（drug 子键→M06 生成审方任务，P2 薄切片）；routing key 拼接见
 * InpatientMessagingConstants.withTypeKey。
 *
 * @param m04OrderNo 医嘱号，非空；来源：开立域业务号
 * @param visitId    住院就诊号，非空；来源：在院就诊关联
 * @param patientId  患者主索引，非空
 * @param orderType  医嘱类型（drug/lab/exam/surgery/blood/nursing/diet/consult/discharge-med 九类小写），非空
 * @param orderClass 医嘱分类（长期 LONG/临时 STAT 等字典值），非空
 * @param standbyFlag 备用嘱标记（prn 嘱托），true=备用嘱
 * @param groupNo    成组医嘱组号，成组医嘱非空、单条医嘱为 null
 * @param freqCode   频次编码（频次专业字典），长期医嘱非空、临时医嘱为 null
 * @param items      医嘱明细行，非空；来源：开立面主子表保存的子表行
 */
public record OrderCreatedPayload(
        String m04OrderNo,
        String visitId,
        long patientId,
        String orderType,
        String orderClass,
        boolean standbyFlag,
        String groupNo,
        String freqCode,
        List<OrderCreatedItem> items) {}
