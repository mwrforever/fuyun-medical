package com.fuyun.billing.api;

import java.util.List;

/**
 * 结算单来源单据引用组（SettlementQueryPort.sourceRefsOfSettlement 返回投影，M03 收费编排反查面）：
 * fee_record 按结算单 id 归档后按 trigger_point 分组的来源单据清单——ORDER_CONFIRMED 组即申请单号
 * （outpatient.order.created sourceRef），PRESCRIPTION_EFFECTIVE 组即处方号
 * （pharmacy.prescription.created sourceRef）；手工/登记等其他计费点不进本投影（挂号费等非单据
 * 费用无放行对象）。两组均去重升序（A.4.3-17 唯一顺序，重投反查结果逐字一致）。禁增删改
 * （Task 10 收费编排与 Task 11 放行凭证核验消费冻结契约）。
 *
 * @param settlementId 结算单 id（雪花，M13 结算聚合根标识）
 * @param orderRefs    申请单号清单（trigger=ORDER_CONFIRMED 的 source_ref 去重升序）
 * @param rxRefs       处方号清单（trigger=PRESCRIPTION_EFFECTIVE 的 source_ref 去重升序）
 */
public record SettlementSourceRefs(long settlementId, List<String> orderRefs, List<String> rxRefs) {}
