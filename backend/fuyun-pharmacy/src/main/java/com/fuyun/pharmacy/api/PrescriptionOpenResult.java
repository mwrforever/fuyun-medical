package com.fuyun.pharmacy.api;

/**
 * 开方结果投影（PrescriptionVO 四组件最小子集——M03 RX_REF 引用行登记与医生站回显所需面，组件
 * 清单为 Task 12 IT 依赖的冻结面；status 为 CREATED→APPROVED 同事务迁移后终态口径）。
 *
 * @param rxNo             处方号（M03 RX_REF 引用行 ext_ref 直取）
 * @param status           处方状态（同事务放行后恒 APPROVED；预检拦截面随 P3 审方引擎扩展）
 * @param reviewLevel      预检分级（P1 预检占位恒 PASS，分级结果字段结构预留）
 * @param skinTestRequired 皮试要求（请求侧显式与明细药品皮试标识的聚合结果）
 */
public record PrescriptionOpenResult(String rxNo, String status, String reviewLevel, boolean skinTestRequired) {}
