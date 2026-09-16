package com.fuyun.patient.api;

/**
 * patient.merged 载荷（V105 id=11 冻结契约）：合并完成广播，订阅方幂等刷新本地映射/宽表；
 * 成对语义（M-25）——凡订阅本事件的模块必须成对登记订阅 patient.split。
 *
 * @param survivorPatientId 主档 id；来源：合并请求
 * @param mergedPatientId   从档 id（已置 MERGED，merged_into 指向主档）；来源：合并请求
 */
public record PatientMergedPayload(long survivorPatientId, long mergedPatientId) {}
