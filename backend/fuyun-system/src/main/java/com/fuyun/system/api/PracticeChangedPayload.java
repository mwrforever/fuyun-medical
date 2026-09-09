package com.fuyun.system.api;

/**
 * 执业授权变更事件载荷（system.practice.changed，CF-2 首批主数据事件，M01 Spec §7 明示）。
 *
 * <p>触发场景：practice_grant 状态机变更（EFFECTIVE → SUSPENDED 吊销/停权、EFFECTIVE → EXPIRED
 * 到期自动）全程留痕并广播，药事/手麻等订阅方据此收敛处方权校验口径（M01 Spec §5）。
 * 事件对象定义在发布方 api 包（backend 宪法 B.3-1），字段名全集经 SystemMasterDataPayloadTest
 * 线格式冻结断言。
 *
 * <p>占位 schema：正式字段随 PR-3 M01 实装冻结，event_registry 登记行 payload_desc 同步。
 *
 * <p>record 纯数据载体（backend 宪法 A.1-2 透明浅不可变）。
 *
 * @param employeeId 员工 ID（雪花 ID），非空；JSON 线格式经 Long→String 全局定制以字符串承载（A.3-8）
 * @param grantType  授权类型（处方权/麻精处方权/抗菌药分级/手术分级，Spec 字段），非空
 * @param status     变更后的授权状态（EFFECTIVE/SUSPENDED/EXPIRED），非空
 */
public record PracticeChangedPayload(Long employeeId, String grantType, String status) {}
