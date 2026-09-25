package com.fuyun.inpatient.api.payload;

/**
 * 床位动态变更事件载荷（inpatient.bed.changed，V800 id 52 冻结契约）：床位五态任一迁移
 * （预占/占床/释放/消毒流转/消毒完成/维修/维修恢复/转出/转入）事务提交后广播，M05 据此
 * 维护护士站一览与大屏床位动态。脱敏红线：仅定位键与状态，禁患者姓名/诊断文本。
 *
 * @param wardId    床位归属病区编码，非空；来源：床位行 ward_id
 * @param bedId     床位 id，非空；来源：床位行主键
 * @param bedNo     床号，非空；来源：床位行 bed_no
 * @param bedStatus 迁移后床位状态 code（BedStatus 五态），非空；来源：CAS 目标态
 * @param patientId 占用患者主索引，可空；占床/转入时来源就诊行，预占/释放/消毒/维修等无主体场景为 null
 */
public record BedChangedPayload(String wardId, long bedId, String bedNo, String bedStatus, Long patientId) {}
